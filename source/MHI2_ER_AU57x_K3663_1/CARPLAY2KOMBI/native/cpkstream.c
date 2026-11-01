/*
 * CARPLAY2KOMBI live streaming, linked into libcpkhook.so.
 *
 * While /ramdisk/cpk/stream exists, each H.264 frame from the encoder in cpkenc.c
 * is packed into MPEG-TS the way the cluster takes it (program 1, PMT PID 0x1000,
 * PCR PID 0x100, video PID 0x1011) and padded with null packets to whole blocks of
 * 64 packets (12032 bytes), the only write size the MOST driver accepts.
 * The blocks go through a lock-free ring to a writer thread that writes them to
 * /dev/mlb/isoTX2, so CarPlay's own display never waits for the MOST bus. With no
 * new frames (a still screen) the thread sends a PCR every 100 ms; every frame
 * carries the tables itself.
 *
 * The cluster has to be on a context without the map (dmdt sc 4 77) while this
 * runs, and back on the map (dmdt sc 4 73) after; cpk-cluster.sh does both.
 */

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef unsigned long long u64;

struct timespec_qnx {
	long tv_sec;
	long tv_nsec;
};

extern int access(const char *path, int mode);
extern int open(const char *path, int flags, ...);
extern int write(int fd, const void *data, u32 size);
extern int close(int fd);
extern int usleep(u32 microseconds);
extern int clock_gettime(int clock, struct timespec_qnx *now);
extern int pthread_create(int *thread, const void *attributes, void *(*start)(void *), void *argument);
extern int pthread_detach(int thread);
extern void *memcpy(void *to, const void *from, u32 size);
extern void *memset(void *to, int value, u32 size);

#define STREAM_TRIGGER "/ramdisk/cpk/stream"
#define DEVICE "/dev/mlb/isoTX2"

#define PACKET 188
#define PACKETS_PER_BLOCK 64
#define BLOCK (PACKETS_PER_BLOCK * PACKET)
/* 32 blocks of 12032 bytes: about a second at the stream's rate */
#define RING_BLOCKS 32
/* Largest frame we pack: 8 blocks, about 90 KB */
#define STAGE_BLOCKS 8

#define PID_PAT 0x0000
#define PID_PMT 0x1000
#define PID_PCR 0x100
#define PID_VIDEO 0x1011
#define PID_NULL 0x1fff
#define PROGRAM 1
#define STREAM_TYPE_H264 0x1b

/* PTS runs this far ahead of the PCR, in 90 kHz ticks (60 ms): the cluster shows each frame this late */
#define PTS_DELAY 5400
#define KEEPALIVE_US 100000
#define TRIGGER_CHECK_US 50000

#define CLOCK_REALTIME 0
/* QNX: CLOCK_MONOTONIC is 2; checked once, else realtime */
#define CLOCK_MONOTONIC 2

static u8 ring[RING_BLOCKS][BLOCK];
/* Single producer (CarPlay's render thread), single consumer (the writer) */
static volatile u32 ring_head;
static volatile u32 ring_tail;

static u8 stage[STAGE_BLOCKS * BLOCK];
static u8 keepalive[BLOCK];

/* Continuity counters of the PIDs with payload, used by the render thread only */
static u8 cc_pat, cc_pmt, cc_video;

static volatile int streaming;
static volatile int need_keyframe;
static int clock_id = -1;
static int writer_started;

/* The stream clock in 90 kHz ticks, without 64-bit division */
static u64 now_90k(void)
{
	struct timespec_qnx t;

	if (clock_id < 0)
		clock_id = clock_gettime(CLOCK_MONOTONIC, &t) == 0 ? CLOCK_MONOTONIC : CLOCK_REALTIME;
	clock_gettime(clock_id, &t);
	return (u64)(u32)t.tv_sec * 90000u + (u32)t.tv_nsec / 11111u;
}

static u32 crc32_mpeg(const u8 *data, u32 len)
{
	u32 crc = 0xffffffffu;
	u32 i;
	int bit;

	for (i = 0; i < len; i++) {
		crc ^= (u32)data[i] << 24;
		for (bit = 0; bit < 8; bit++)
			crc = (crc & 0x80000000u) ? (crc << 1) ^ 0x04c11db7u : crc << 1;
	}
	return crc;
}

/*
 * One packet at out. payload NULL with pcr set: adaptation only, carrying the PCR
 * (no continuity counter step). A short payload is padded by the adaptation field
 */
static void put_packet(u8 *out, u32 pid, int start, const u8 *payload, u32 len, u8 *cc, const u64 *pcr)
{
	u32 af_len;
	u8 *p = out;

	*p++ = 0x47;
	*p++ = (u8)((start ? 0x40 : 0) | (pid >> 8));
	*p++ = (u8)pid;
	if (!payload) {
		/* Adaptation field only: 183 bytes with the PCR flag and 6 PCR bytes */
		u64 base = *pcr & 0x1ffffffffull;

		*p++ = (u8)(0x20 | (*cc & 0x0f));
		*p++ = 183;
		*p++ = 0x10;
		*p++ = (u8)(base >> 25);
		*p++ = (u8)(base >> 17);
		*p++ = (u8)(base >> 9);
		*p++ = (u8)(base >> 1);
		*p++ = (u8)(((base & 1) << 7) | 0x7e);
		*p++ = 0;
		memset(p, 0xff, (u32)(out + PACKET - p));
		return;
	}
	*p++ = (u8)((len < 184 ? 0x30 : 0x10) | (*cc & 0x0f));
	*cc = (u8)((*cc + 1) & 0x0f);
	if (len < 184) {
		af_len = 183 - len;
		*p++ = (u8)af_len;
		if (af_len) {
			*p++ = 0;
			memset(p, 0xff, af_len - 1);
			p += af_len - 1;
		}
	}
	memcpy(p, payload, len);
}

/* A PSI section (PAT or PMT) in one packet */
static void put_psi(u8 *out, u32 pid, u8 table_id, u32 ext, const u8 *body, u32 body_len, u8 *cc)
{
	u8 section[184];
	u32 len = 0, crc;

	section[len++] = 0; /* pointer field */
	section[len++] = table_id;
	section[len++] = (u8)(0xb0 | ((body_len + 9) >> 8));
	section[len++] = (u8)(body_len + 9);
	section[len++] = (u8)(ext >> 8);
	section[len++] = (u8)ext;
	section[len++] = 0xc1; /* version 0, current */
	section[len++] = 0;
	section[len++] = 0;
	memcpy(section + len, body, body_len);
	len += body_len;
	crc = crc32_mpeg(section + 1, len - 1);
	section[len++] = (u8)(crc >> 24);
	section[len++] = (u8)(crc >> 16);
	section[len++] = (u8)(crc >> 8);
	section[len++] = (u8)crc;
	memset(section + len, 0xff, 184 - len);
	put_packet(out, pid, 1, section, 184, cc, 0);
}

/* PAT, PMT and a PCR packet: 3 packets */
static u32 put_tables(u8 *out, u64 pcr, u8 *cc_a, u8 *cc_b)
{
	static const u8 pat[] = { 0, PROGRAM, 0xe0 | (PID_PMT >> 8), PID_PMT & 0xff };
	static const u8 pmt[] = {
		0xe0 | (PID_PCR >> 8), PID_PCR & 0xff, 0xf0, 0,
		STREAM_TYPE_H264, 0xe0 | (PID_VIDEO >> 8), PID_VIDEO & 0xff, 0xf0, 0,
	};
	u8 no_cc = 0;

	put_psi(out, PID_PAT, 0x00, PROGRAM, pat, sizeof(pat), cc_a);
	put_psi(out + PACKET, PID_PMT, 0x02, PROGRAM, pmt, sizeof(pmt), cc_b);
	put_packet(out + 2 * PACKET, PID_PCR, 0, 0, 0, &no_cc, &pcr);
	return 3;
}

/* Null packets from packet index used up to the end of the block */
static u32 pad_block(u8 *out, u32 used)
{
	u8 no_cc = 0;
	static const u8 nothing[184];

	while (used % PACKETS_PER_BLOCK) {
		put_packet(out + used * PACKET, PID_NULL, 0, nothing, 184, &no_cc, 0);
		used++;
	}
	return used;
}

/* Packs one access unit into stage, padded to whole blocks. Returns blocks, 0 if too big */
static u32 pack_frame(const u8 *data, u32 len, u64 pcr)
{
	u8 header[14];
	u64 pts = (pcr + PTS_DELAY) & 0x1ffffffffull;
	u32 used, chunk, first_len, packets_needed;

	/* PES header with PTS; length 0 = unbounded, allowed for video */
	header[0] = 0;
	header[1] = 0;
	header[2] = 1;
	header[3] = 0xe0;
	header[4] = 0;
	header[5] = 0;
	header[6] = 0x80;
	header[7] = 0x80;
	header[8] = 5;
	header[9] = (u8)(0x21 | ((pts >> 29) & 0x0e));
	header[10] = (u8)(pts >> 22);
	header[11] = (u8)(0x01 | ((pts >> 14) & 0xfe));
	header[12] = (u8)(pts >> 7);
	header[13] = (u8)(0x01 | ((pts << 1) & 0xfe));

	packets_needed = 3 + (len + sizeof(header) + 183) / 184;
	if (packets_needed > STAGE_BLOCKS * PACKETS_PER_BLOCK)
		return 0;

	used = put_tables(stage, pcr, &cc_pat, &cc_pmt);

	/* First packet: PES header and the start of the frame */
	first_len = len < 184 - sizeof(header) ? len : 184 - sizeof(header);
	{
		u8 first[184];

		memcpy(first, header, sizeof(header));
		memcpy(first + sizeof(header), data, first_len);
		put_packet(stage + used * PACKET, PID_VIDEO, 1, first, sizeof(header) + first_len, &cc_video, 0);
		used++;
	}
	data += first_len;
	len -= first_len;
	while (len) {
		chunk = len < 184 ? len : 184;
		put_packet(stage + used * PACKET, PID_VIDEO, 0, data, chunk, &cc_video, 0);
		used++;
		data += chunk;
		len -= chunk;
	}
	return pad_block(stage, used) / PACKETS_PER_BLOCK;
}

static void *writer(void *argument)
{
	int fd = -1;
	int was_streaming = 0;
	u32 since_check = TRIGGER_CHECK_US;
	u64 last_write = 0;

	(void)argument;
	for (;;) {
		if (since_check >= TRIGGER_CHECK_US) {
			streaming = access(STREAM_TRIGGER, 0) == 0;
			since_check = 0;
		}
		if (!streaming) {
			if (fd >= 0)
				close(fd);
			fd = -1;
			was_streaming = 0;
			usleep(TRIGGER_CHECK_US);
			since_check = TRIGGER_CHECK_US;
			continue;
		}
		if (!was_streaming) {
			/* Drop anything left from before and start with a keyframe */
			ring_tail = ring_head;
			need_keyframe = 1;
			was_streaming = 1;
		}
		if (fd < 0) {
			fd = open(DEVICE, 1);
			if (fd < 0) {
				usleep(200000);
				since_check += 200000;
				continue;
			}
		}
		if (ring_tail != ring_head) {
			write(fd, ring[ring_tail % RING_BLOCKS], BLOCK);
			__sync_synchronize();
			ring_tail = ring_tail + 1;
			last_write = now_90k();
			continue;
		}
		if (now_90k() - last_write > KEEPALIVE_US * 9 / 100) {
			/* Only a PCR: it doesn't step a continuity counter, so it can't clash with the frames */
			u64 pcr = now_90k();
			u8 no_cc = 0;

			put_packet(keepalive, PID_PCR, 0, 0, 0, &no_cc, &pcr);
			pad_block(keepalive, 1);
			write(fd, keepalive, BLOCK);
			last_write = pcr;
		}
		usleep(5000);
		since_check += 5000;
	}
	return 0;
}

/* True while streaming is on. Starts the writer thread the first time */
__attribute__((visibility("hidden"))) int cpkstream_active(void)
{
	int thread;

	if (!writer_started) {
		if (access(STREAM_TRIGGER, 0) != 0)
			return 0;
		writer_started = 1;
		if (pthread_create(&thread, 0, writer, 0) == 0)
			pthread_detach(thread);
	}
	return streaming;
}

/* True once when the next frame should be a keyframe (stream start or a dropped frame) */
__attribute__((visibility("hidden"))) int cpkstream_take_keyframe(void)
{
	if (!need_keyframe)
		return 0;
	need_keyframe = 0;
	return 1;
}

/* One encoded frame from the encoder: pack it and queue it for the writer */
__attribute__((visibility("hidden"))) void cpkstream_frame(const u8 *data, u32 len)
{
	u32 blocks, i;

	if (!streaming)
		return;
	blocks = pack_frame(data, len, now_90k());
	if (!blocks || ring_head - ring_tail + blocks > RING_BLOCKS) {
		need_keyframe = 1;
		return;
	}
	for (i = 0; i < blocks; i++)
		memcpy(ring[(ring_head + i) % RING_BLOCKS], stage + i * BLOCK, BLOCK);
	__sync_synchronize();
	ring_head = ring_head + blocks;
}
