/*
 * CARPLAY2KOMBI live streaming, linked into libcpkhook.so.
 *
 * While /ramdisk/cpk/stream exists, a writer thread sends one continuous MPEG-TS
 * stream at a constant rate to /dev/mlb/isoTX2, in whole blocks of 64 packets
 * (12032 bytes), the only write size the MOST driver accepts. Every block starts
 * with PAT, PMT (program 1, PMT PID 0x1000) and a PCR (PID 0x100); the rest is
 * video (PID 0x1011) from a packet queue, or null packets when the queue is empty.
 *
 * Frames from the encoder in cpkenc.c go into the queue without padding, so small
 * frames share a block instead of each taking a whole one. The PCR counts the
 * packets sent at the stream rate. When the writer puts a frame's first packet into a
 * block, it stamps the frame with a PTS a fixed lead ahead of that packet's PCR, so
 * the cluster shows it on time instead of only when the next frame arrives.
 * CarPlay's own display never waits for the MOST bus.
 *
 * Settings in /ramdisk/cpk, read when streaming starts (cpk-cluster.sh writes them):
 *   delay   ms the PTS runs ahead (default 50), or nopts for frames without a PTS
 *   rate    stream rate in kbit/s (default 4000)
 *   repeat  repeats of the last picture after each CarPlay change (default 0)
 * Every 2 s the writer appends its counters to /ramdisk/cpk/stream.log.
 *
 * The cluster has to be on a context without the map (dmdt sc 4 77) while this
 * runs, and back on the map (dmdt sc 4 73) after; cpk-cluster.sh does both.
 */

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef unsigned long long u64;
typedef struct FILE FILE;

struct timespec_qnx {
	long tv_sec;
	long tv_nsec;
};

extern int access(const char *path, int mode);
extern int open(const char *path, int flags, ...);
extern int read(int fd, void *data, u32 size);
extern int write(int fd, const void *data, u32 size);
extern int close(int fd);
extern int usleep(u32 microseconds);
extern int clock_gettime(int clock, struct timespec_qnx *now);
extern int pthread_create(int *thread, const void *attributes, void *(*start)(void *), void *argument);
extern int pthread_detach(int thread);
extern void *memcpy(void *to, const void *from, u32 size);
extern void *memset(void *to, int value, u32 size);
extern FILE *fopen(const char *path, const char *mode);
extern int fprintf(FILE *file, const char *format, ...);
extern int fflush(FILE *file);
extern int fclose(FILE *file);

#define STREAM_TRIGGER "/ramdisk/cpk/stream"
#define DEVICE "/dev/mlb/isoTX2"
#define STREAM_LOG "/ramdisk/cpk/stream.log"

#define PACKET 188
#define PACKETS_PER_BLOCK 64
#define BLOCK (PACKETS_PER_BLOCK * PACKET)
/* PAT, PMT and the PCR start every block */
#define TABLE_PACKETS 3
/* Video packets waiting for the writer (a power of two): about 385 KB */
#define QUEUE_PACKETS 2048
#define QUEUE_MASK (QUEUE_PACKETS - 1)

#define PID_PAT 0x0000
#define PID_PMT 0x1000
#define PID_PCR 0x100
#define PID_VIDEO 0x1011
#define PID_NULL 0x1fff
#define PROGRAM 1
#define STREAM_TYPE_H264 0x1b

#define DELAY_FILE "/ramdisk/cpk/delay"
#define DELAY_DEFAULT_MS 50
#define DELAY_MAX_MS 1000
#define RATE_FILE "/ramdisk/cpk/rate"
#define RATE_DEFAULT_KBPS 4000
#define RATE_MIN_KBPS 1000
#define RATE_MAX_KBPS 20000
#define REPEAT_FILE "/ramdisk/cpk/repeat"
#define REPEAT_DEFAULT 0
#define REPEAT_MAX 30
/* A frame that would wait longer than this behind others is dropped, and an IDR follows */
#define QUEUE_LIMIT_MS 150
#define QUEUE_LIMIT_MIN 128

/* Bits in a packet and in a block, for the rate maths */
#define PACKET_BITS (PACKET * 8)
#define BLOCK_BITS (BLOCK * 8)
/* 90 kHz ticks per packet = PACKET_BITS * 90000 / bps; this is that times 4096 times kbit/s */
#define TICKS_Q12_PER_KBPS 554434560u

/* The PCR of the first block of a stream (1 s) */
#define PCR_START 90000u
#define TRIGGER_CHECK_US 50000
/* When the writer is this far behind its schedule, it starts the schedule again */
#define LATE_RESET_US 100000
#define STATS_US 2000000
#define LOG_MAX_LINES 200

#define CLOCK_REALTIME 0
/* QNX: CLOCK_MONOTONIC is 2; checked once, else realtime */
#define CLOCK_MONOTONIC 2

static u8 queue[QUEUE_PACKETS][PACKET];
/* Packet counters. Single producer (the encoder, under its lock), single consumer (the writer) */
static volatile u32 queue_head;
static volatile u32 queue_tail;

static u8 block[BLOCK];
static u8 null_packet[PACKET];

/* Continuity counters: video in the producer, PAT and PMT in the writer */
static u8 cc_video, cc_pat, cc_pmt;

static volatile int streaming;
static volatile int need_keyframe;

/*
 * Set by the writer when streaming starts, read by the producer. They start at the
 * defaults, so a frame that comes in just before the writer has read the settings is
 * still packed sensibly
 */
static volatile int use_pts = 1;
static volatile u32 pts_lead = DELAY_DEFAULT_MS * 90u;
static volatile u32 ticks_q12 = TICKS_Q12_PER_KBPS / RATE_DEFAULT_KBPS;
static volatile u32 queue_limit = RATE_DEFAULT_KBPS * QUEUE_LIMIT_MS / PACKET_BITS;
static volatile u32 repeat_limit = REPEAT_DEFAULT;
/* Frames the producer dropped because the queue was too long */
static volatile u32 dropped_frames;

static int clock_id = -1;
static int writer_started;

/*
 * n / d by shifting and subtracting: the MMX's Cortex-A9 has no divide instruction in
 * ARM mode, and the hook links no compiler runtime to do it
 */
static u32 udiv32(u32 n, u32 d)
{
	u32 q = 0, r = 0;
	int bit;

	for (bit = 31; bit >= 0; bit--) {
		r = (r << 1) | ((n >> bit) & 1);
		if (r >= d) {
			r -= d;
			q |= 1u << bit;
		}
	}
	return q;
}

/* Microseconds on the monotonic clock, without 64-bit division */
static u64 now_us(void)
{
	struct timespec_qnx t;

	if (clock_id < 0)
		clock_id = clock_gettime(CLOCK_MONOTONIC, &t) == 0 ? CLOCK_MONOTONIC : CLOCK_REALTIME;
	clock_gettime(clock_id, &t);
	return (u64)(u32)t.tv_sec * 1000000u + (u32)t.tv_nsec / 1000u;
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
 * One packet with len bytes of payload (at most 184). A shorter payload is padded by
 * an adaptation field; flags (for example random access) also need one, so with flags
 * the payload must be at most 182 bytes
 */
static void put_packet(u8 *out, u32 pid, int start, const u8 *payload, u32 len, u8 *cc, u8 flags)
{
	u32 af_len;
	u8 *p = out;

	*p++ = 0x47;
	*p++ = (u8)((start ? 0x40 : 0) | (pid >> 8));
	*p++ = (u8)pid;
	if (len == 184 && !flags) {
		*p++ = (u8)(0x10 | (*cc & 0x0f));
	} else {
		*p++ = (u8)(0x30 | (*cc & 0x0f));
		af_len = 183 - len;
		*p++ = (u8)af_len;
		if (af_len) {
			*p++ = flags;
			memset(p, 0xff, af_len - 1);
			p += af_len - 1;
		}
	}
	*cc = (u8)((*cc + 1) & 0x0f);
	memcpy(p, payload, len);
}

/*
 * A PCR-only packet: no payload, so no continuity counter step. The first one of a
 * stream says the clock jumped, because the map stream before ran on another clock
 */
static void put_pcr(u8 *out, u32 pcr, int discontinuity)
{
	u8 *p = out;

	*p++ = 0x47;
	*p++ = (u8)(PID_PCR >> 8);
	*p++ = (u8)PID_PCR;
	*p++ = 0x20;
	*p++ = 183;
	*p++ = (u8)(0x10 | (discontinuity ? 0x80 : 0));
	/* 33-bit base from a 32-bit value: the top bit stays 0, so the clock wraps after 13 hours */
	*p++ = (u8)(pcr >> 25);
	*p++ = (u8)(pcr >> 17);
	*p++ = (u8)(pcr >> 9);
	*p++ = (u8)(pcr >> 1);
	*p++ = (u8)(((pcr & 1) << 7) | 0x7e);
	*p++ = 0;
	memset(p, 0xff, (u32)(out + PACKET - p));
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

/* A number from a settings file: -1 without the file, -2 if it doesn't hold a number up to max */
static int read_number(const char *path, int max)
{
	char text[16];
	int fd = open(path, 0);
	int n, i, value = 0;

	if (fd < 0)
		return -1;
	n = read(fd, text, sizeof(text) - 1);
	close(fd);
	for (i = 0; i < n && i < 6 && text[i] >= '0' && text[i] <= '9'; i++)
		value = value * 10 + (text[i] - '0');
	if (i == 0 || (i < n && text[i] >= '0' && text[i] <= '9') || value > max)
		return -2;
	return value;
}

/*
 * Reads the settings and works out the rate's numbers, when streaming starts.
 * Returns the time one block takes on the channel, in microseconds
 */
static u32 read_settings(u32 *rate_out)
{
	int delay = read_number(DELAY_FILE, DELAY_MAX_MS);
	int rate = read_number(RATE_FILE, RATE_MAX_KBPS);
	int repeat = read_number(REPEAT_FILE, REPEAT_MAX);
	u32 kbps = rate >= RATE_MIN_KBPS ? (u32)rate : RATE_DEFAULT_KBPS;
	u32 limit = udiv32(kbps * QUEUE_LIMIT_MS, PACKET_BITS);

	/* No file: the default lead. Something that isn't a number (nopts): frames without a PTS */
	use_pts = delay != -2;
	pts_lead = (delay >= 0 ? (u32)delay : DELAY_DEFAULT_MS) * 90u;
	ticks_q12 = udiv32(TICKS_Q12_PER_KBPS, kbps);
	if (limit < QUEUE_LIMIT_MIN)
		limit = QUEUE_LIMIT_MIN;
	if (limit > QUEUE_PACKETS - 1)
		limit = QUEUE_PACKETS - 1;
	queue_limit = limit;
	repeat_limit = repeat >= 0 ? (u32)repeat : REPEAT_DEFAULT;
	*rate_out = kbps;
	return udiv32(BLOCK_BITS * 1000u, kbps);
}

/*
 * Writes the PTS into a video packet that starts a frame, if the producer left room
 * for one. PCR and PTS share the 32-bit clock, so they wrap together
 */
static void set_pts(u8 *packet, u32 pts)
{
	u8 *pes = packet + 4;

	if (packet[3] & 0x20)
		pes += 1 + packet[4];
	if (pes + 14 > packet + PACKET || !(pes[7] & 0x80))
		return;
	pes[9] = (u8)(0x21 | ((pts >> 29) & 0x0e));
	pes[10] = (u8)(pts >> 22);
	pes[11] = (u8)(0x01 | ((pts >> 14) & 0xfe));
	pes[12] = (u8)(pts >> 7);
	pes[13] = (u8)(0x01 | ((pts << 1) & 0xfe));
}

/*
 * Builds the next block from the PCR clock at its first packet: PAT, PMT and the PCR,
 * then video packets from the queue, then null packets. A frame's first packet gets
 * its PTS here, the lead ahead of its own PCR. Returns the number of video packets
 */
static u32 fill_block(u64 pcr_q12, int discontinuity)
{
	static const u8 pat[] = { 0, PROGRAM, 0xe0 | (PID_PMT >> 8), PID_PMT & 0xff };
	static const u8 pmt[] = {
		0xe0 | (PID_PCR >> 8), PID_PCR & 0xff, 0xf0, 0,
		STREAM_TYPE_H264, 0xe0 | (PID_VIDEO >> 8), PID_VIDEO & 0xff, 0xf0, 0,
	};
	u32 i, video = 0;

	put_psi(block, PID_PAT, 0x00, PROGRAM, pat, sizeof(pat), &cc_pat);
	put_psi(block + PACKET, PID_PMT, 0x02, PROGRAM, pmt, sizeof(pmt), &cc_pmt);
	put_pcr(block + 2 * PACKET, (u32)((pcr_q12 + (u64)ticks_q12 * 2) >> 12), discontinuity);
	for (i = TABLE_PACKETS; i < PACKETS_PER_BLOCK; i++) {
		if (queue_tail != queue_head) {
			u8 *packet = block + i * PACKET;

			memcpy(packet, queue[queue_tail & QUEUE_MASK], PACKET);
			__sync_synchronize();
			queue_tail = queue_tail + 1;
			if (use_pts && (packet[1] & 0x40))
				set_pts(packet, (u32)((pcr_q12 + (u64)ticks_q12 * i) >> 12) + pts_lead);
			video++;
		} else {
			memcpy(block + i * PACKET, null_packet, PACKET);
		}
	}
	return video;
}

static void *writer(void *argument)
{
	FILE *stats_log = 0;
	int fd = -1;
	int was_streaming = 0;
	int discontinuity = 0;
	u32 since_check = TRIGGER_CHECK_US;
	u32 block_us = 0, kbps = 0, log_lines = 0;
	u64 pcr_q12 = 0, due = 0, stats_start = 0, start, end;
	/* Counters since the last stats line */
	u32 blocks = 0, video = 0, max_backlog = 0, write_total = 0, write_max = 0, dropped_seen = 0;

	(void)argument;
	null_packet[0] = 0x47;
	null_packet[1] = (u8)(PID_NULL >> 8);
	null_packet[2] = (u8)PID_NULL;
	null_packet[3] = 0x10;
	memset(null_packet + 4, 0xff, PACKET - 4);

	for (;;) {
		if (since_check >= TRIGGER_CHECK_US) {
			streaming = access(STREAM_TRIGGER, 0) == 0;
			since_check = 0;
		}
		if (!streaming) {
			if (fd >= 0)
				close(fd);
			if (stats_log)
				fclose(stats_log);
			fd = -1;
			stats_log = 0;
			was_streaming = 0;
			usleep(TRIGGER_CHECK_US);
			since_check = TRIGGER_CHECK_US;
			continue;
		}
		if (!was_streaming) {
			/* Drop anything left from before and start with a keyframe on a new clock */
			block_us = read_settings(&kbps);
			pcr_q12 = (u64)PCR_START << 12;
			queue_tail = queue_head;
			need_keyframe = 1;
			discontinuity = 1;
			due = now_us();
			stats_start = due;
			dropped_seen = dropped_frames;
			blocks = video = max_backlog = write_total = write_max = 0;
			stats_log = fopen(STREAM_LOG, "a");
			log_lines = 0;
			if (stats_log) {
				fprintf(stats_log, "start: rate %u kbit/s, block %u us, queue limit %u packets, delay %u ms%s, repeats %u\n",
					kbps, block_us, queue_limit, pts_lead / 90u, use_pts ? "" : " (no PTS)", repeat_limit);
				fflush(stats_log);
			}
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

		{
			u32 backlog = queue_head - queue_tail;

			if (backlog > max_backlog)
				max_backlog = backlog;
		}
		video += fill_block(pcr_q12, discontinuity);
		discontinuity = 0;

		start = now_us();
		if (write(fd, block, BLOCK) != BLOCK) {
			/* The video in this block is lost: open the device again and restart with a keyframe */
			close(fd);
			fd = -1;
			need_keyframe = 1;
			usleep(200000);
			since_check += 200000;
			continue;
		}
		end = now_us();
		pcr_q12 += (u64)ticks_q12 * PACKETS_PER_BLOCK;
		blocks++;
		write_total += (u32)(end - start);
		if ((u32)(end - start) > write_max)
			write_max = (u32)(end - start);

		if (stats_log && end - stats_start >= STATS_US) {
			u32 elapsed_ms = (u32)(end - stats_start) / 1000u;
			u32 drops = dropped_frames - dropped_seen;

			if (log_lines < LOG_MAX_LINES) {
				log_lines++;
				/* blocks * 96256 bits / ms = kbit/s actually sent */
				fprintf(stats_log, "%u blocks %u kbit/s, video %u of %u packets, backlog max %u, drops %u, write avg %u max %u us\n",
					blocks, udiv32(blocks * (BLOCK_BITS / 16u), elapsed_ms) * 16u, video,
					blocks * (PACKETS_PER_BLOCK - TABLE_PACKETS), max_backlog, drops,
					udiv32(write_total, blocks), write_max);
				fflush(stats_log);
			}
			stats_start = end;
			dropped_seen += drops;
			blocks = video = max_backlog = write_total = write_max = 0;
		}

		/*
		 * Keep the constant rate: wait for the next block's time. When the driver has held
		 * the writer back so long that it is far behind, start the schedule again from now
		 */
		due += block_us;
		if (end > due + LATE_RESET_US) {
			due = end;
		} else if (end < due) {
			usleep((u32)(due - end));
		}
		since_check += block_us;
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

/* How many repeats the encoder may send after each CarPlay frame */
__attribute__((visibility("hidden"))) u32 cpkstream_repeat_limit(void)
{
	return repeat_limit;
}

/* True once when the next frame should be a keyframe (stream start or a dropped frame) */
__attribute__((visibility("hidden"))) int cpkstream_take_keyframe(void)
{
	if (!need_keyframe)
		return 0;
	need_keyframe = 0;
	return 1;
}

/* True if the access unit's first slice is an IDR (NAL type 5) */
static int is_keyframe(const u8 *data, u32 len)
{
	u32 i, type;

	for (i = 0; i + 3 < len; i++) {
		if (data[i] != 0 || data[i + 1] != 0 || data[i + 2] != 1)
			continue;
		type = data[i + 3] & 0x1f;
		if (type >= 1 && type <= 5)
			return type == 5;
	}
	return 0;
}

/* One encoded frame from the encoder: pack it into the queue for the writer */
__attribute__((visibility("hidden"))) void cpkstream_frame(const u8 *data, u32 len)
{
	u8 header[14];
	u32 header_len, packets, backlog, slot, chunk, room;
	u8 flags;

	if (!streaming)
		return;

	/* PES header, length 0 = unbounded, allowed for video */
	header[0] = 0;
	header[1] = 0;
	header[2] = 1;
	header[3] = 0xe0;
	header[4] = 0;
	header[5] = 0;
	header[6] = 0x80;
	header[7] = 0;
	header[8] = 0;
	header_len = 9;
	if (use_pts) {
		/* Room for the PTS, which the writer fills in (see set_pts) */
		header[7] = 0x80;
		header[8] = 5;
		header[9] = 0x21;
		header[10] = 0;
		header[11] = 0x01;
		header[12] = 0;
		header[13] = 0x01;
		header_len = 14;
	}

	/* The first packet has an adaptation field for the random access flag: 182 bytes */
	packets = len + header_len <= 182 ? 1 : 1 + (len + header_len - 182 + 183) / 184;
	backlog = queue_head - queue_tail;
	/* An empty queue always takes the frame, so a big keyframe can't be stuck out */
	if (packets > QUEUE_PACKETS - backlog || (backlog && backlog + packets > queue_limit)) {
		dropped_frames = dropped_frames + 1;
		need_keyframe = 1;
		return;
	}

	slot = queue_head;
	/* Random access indicator on a keyframe's first packet */
	flags = is_keyframe(data, len) ? 0x40 : 0;
	{
		u8 payload[184];

		room = 182 - header_len;
		chunk = len < room ? len : room;
		memcpy(payload, header, header_len);
		memcpy(payload + header_len, data, chunk);
		/* A zero flags byte still keeps the adaptation field, so the first packet is always 182 bytes */
		put_packet(queue[slot & QUEUE_MASK], PID_VIDEO, 1, payload, header_len + chunk, &cc_video, flags);
		slot++;
		data += chunk;
		len -= chunk;
	}
	while (len) {
		chunk = len < 184 ? len : 184;
		put_packet(queue[slot & QUEUE_MASK], PID_VIDEO, 0, data, chunk, &cc_video, 0);
		slot++;
		data += chunk;
		len -= chunk;
	}
	__sync_synchronize();
	queue_head = slot;
}
