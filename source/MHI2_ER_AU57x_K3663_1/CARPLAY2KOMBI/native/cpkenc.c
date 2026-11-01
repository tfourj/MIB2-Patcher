/*
 * CARPLAY2KOMBI hardware encoder, linked into libcpkhook.so.
 *
 * While /ramdisk/cpk/enc or /ramdisk/cpk/stream exists, every decoded CarPlay frame
 * that libnvss_video hands to NvMediaVideoMixerRender is scaled by a second NvMedia
 * mixer into an 800x298 RGBA surface (the whole picture as 496x298 in the middle,
 * black sides) and encoded by the Tegra's H.264 encoder, the same path as the cluster
 * map. With enc the output is recorded to /ramdisk/cpk/enc.h264; with stream it goes
 * to cpkstream.c for the cluster. Status lines go to /ramdisk/cpk/enc.log.
 *
 * Signatures and struct offsets are from libnvss_video's and libnvvsenc's calls into
 * libnvmedia.so (see docs/CARPLAY2KOMBI/FINDINGS.md). NVIDIA's libraries pass floats
 * in integer registers (softfp), so the mixer's aspect ratio goes in as its bits.
 */

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef struct FILE FILE;

extern void *dlopen(const char *name, int mode);
extern void *dlsym(void *handle, const char *name);
extern int access(const char *path, int mode);
extern FILE *fopen(const char *path, const char *mode);
extern u32 fwrite(const void *data, u32 size, u32 count, FILE *file);
extern int fprintf(FILE *file, const char *format, ...);
extern int fflush(FILE *file);
extern int fclose(FILE *file);
extern int usleep(u32 microseconds);
extern int pthread_create(int *thread, const void *attributes, void *(*start)(void *), void *argument);
extern int pthread_detach(int thread);
struct timespec_qnx {
	long tv_sec;
	long tv_nsec;
};
extern int clock_gettime(int clock, struct timespec_qnx *now);

/* cpkstream.c: live streaming to the cluster */
extern __attribute__((visibility("hidden"))) int cpkstream_active(void);
extern __attribute__((visibility("hidden"))) int cpkstream_take_keyframe(void);
extern __attribute__((visibility("hidden"))) u32 cpkstream_repeat_limit(void);
extern __attribute__((visibility("hidden"))) void cpkstream_frame(const u8 *data, u32 len);

#define NVMEDIA_LIBRARY "libnvmedia.so"
#define DLOPEN_MODE 0x100

#define ENC_TRIGGER "/ramdisk/cpk/enc"
#define ENC_VIDEO "/ramdisk/cpk/enc.h264"
#define ENC_LOG "/ramdisk/cpk/enc.log"
#define ENC_MAX_BYTES (6u * 1024u * 1024u)

/* Cluster map window and where the CarPlay picture goes in it */
#define OUT_WIDTH 800
#define OUT_HEIGHT 298
#define PICTURE_X 152
#define PICTURE_WIDTH 496
/* CarPlay's stream size; the decoder's surfaces are padded to 16 */
#define SOURCE_WIDTH 780
#define SOURCE_HEIGHT 468

#define SURFACE_RGBA 4
#define ENCODER_H264 0
#define INPUT_RGBA 4
/* Surface rendering (0x20000) needs DVD mode (0x1000) too */
#define MIXER_FEATURES 0x21000
#define BITRATE 2400000
#define IDR_PERIOD 15
#define PROFILE_BASELINE 66
#define BITS_BUFFER (256u * 1024u)
#define LOG_MAX_LINES 200
/* How long to wait for the encoder's output of the frame just fed, polling */
#define DRAIN_WAIT_US 30000
#define DRAIN_POLL_US 2000
/*
 * Without a PTS the cluster showed a frame only when the next one arrived. With the
 * repeat setting (off by default, cpkstream.c stamps every frame with a PTS), the last
 * picture is encoded again when CarPlay sends nothing for this long (a tiny frame)
 */
#define REPEAT_US 33000
/* While this exists, every frame in, repeat and output is logged with its time in ms */
#define DEBUG_TRIGGER "/ramdisk/cpk/debug"
#define DEBUG_MAX_LINES 3000
/* QNX CLOCK_MONOTONIC */
#define CLOCK_MONOTONIC 2

#define DEBUG(...) \
	do { \
		if (debugging && log_file && debug_lines < DEBUG_MAX_LINES) { \
			debug_lines++; \
			fprintf(log_file, __VA_ARGS__); \
		} \
	} while (0)

#define LOG(...) \
	do { \
		if (log_file && log_lines < LOG_MAX_LINES) { \
			log_lines++; \
			fprintf(log_file, __VA_ARGS__); \
		} \
	} while (0)

/* NvMediaRect: four u16 */
struct nvm_rect {
	u16 x0, y0, x1, y1;
};

/* NvMediaPrimaryVideo, 0x1c bytes; the decoded surface is current at +8 */
struct nvm_primary {
	u32 picture_structure;
	void *next;
	void *current;
	void *previous;
	void *previous2;
	struct nvm_rect *src;
	struct nvm_rect *dst;
};

typedef void *(*device_create_fn)(void);
typedef void *(*surface_create_fn)(void *device, u32 type, u32 width, u32 height);
typedef void *(*mixer_create_fn)(void *device, u32 mixer_width, u32 mixer_height, u32 aspect_bits,
	u32 primary_width, u32 primary_height, u32 z0, u32 z1, u32 z2, u32 z3, u32 z4, u32 z5,
	u32 features, void **outputs);
typedef int (*render_surface_fn)(void *mixer, void *out, void *background, struct nvm_primary *primary,
	void *secondary, void *graphics0, void *graphics1);
typedef void *(*encoder_create_fn)(void *device, u32 codec, void *init, u32 input_format,
	u32 max_input, u32 max_output, void *device2);
typedef int (*encoder_feed_fn)(void *encoder, void *surface, struct nvm_rect *src, void *reserved,
	void *picture);
typedef int (*bits_available_fn)(void *encoder, u32 *bytes, u32 blocking, u32 timeout);
typedef int (*get_bits_fn)(void *encoder, void *reserved, u32 *bytes, void *buffer);

static device_create_fn device_create;
static surface_create_fn surface_create;
static mixer_create_fn mixer_create;
static render_surface_fn render_surface;
static encoder_create_fn encoder_create;
static encoder_feed_fn encoder_feed;
static bits_available_fn bits_available;
static get_bits_fn get_bits;

static void *device;
static void *rgba;
static void *mixer;
static void *encoder;
/* Set when the setup failed: stay off instead of retrying every frame */
static int broken;

/*
 * NvMedia encoder init (libnvvsenc's values) and the config it points to at +0x30.
 * Kept large and zeroed: FeedFrame reads the picture params up to +0x48 (a count at
 * +0x44 and an array pointer at +0x48), and junk there crashes it
 */
static u32 encoder_init[64];
static u32 encoder_config[64];
static u32 picture_params[64];

static FILE *video_file;
static FILE *log_file;
/* Lines written to the log; it stops at LOG_MAX_LINES, so errors on every frame can't fill /ramdisk */
static u32 log_lines;
static u32 debug_lines;
static int debugging;
static u32 written_bytes;
static u32 frames;
/* Frames given to the encoder and frames that came out of it */
static u32 frames_fed;
static u32 frames_out;
static u8 bits[BITS_BUFFER];

static int resolve(void)
{
	void *nvmedia = dlopen(NVMEDIA_LIBRARY, DLOPEN_MODE);

	if (!nvmedia)
		return 0;
	device_create = (device_create_fn)dlsym(nvmedia, "NvMediaDeviceCreate");
	surface_create = (surface_create_fn)dlsym(nvmedia, "NvMediaVideoSurfaceCreate");
	mixer_create = (mixer_create_fn)dlsym(nvmedia, "NvMediaVideoMixerCreate");
	render_surface = (render_surface_fn)dlsym(nvmedia, "NvMediaVideoMixerRenderSurface");
	encoder_create = (encoder_create_fn)dlsym(nvmedia, "NvMediaVideoEncoderCreate");
	encoder_feed = (encoder_feed_fn)dlsym(nvmedia, "NvMediaVideoEncoderFeedFrame");
	bits_available = (bits_available_fn)dlsym(nvmedia, "NvMediaVideoEncoderBitsAvailable");
	get_bits = (get_bits_fn)dlsym(nvmedia, "NvMediaVideoEncoderGetBits");
	return device_create && surface_create && mixer_create && render_surface && encoder_create &&
		encoder_feed && bits_available && get_bits;
}

/* Builds the device, the 800x298 RGBA surface, the mixer and the encoder once */
static int setup(const u32 *source)
{
	/* 800 / 298 as a float, passed in an integer register */
	union {
		float f;
		u32 bits;
	} aspect = { (float)OUT_WIDTH / OUT_HEIGHT };

	if (!resolve()) {
		LOG("setup: NvMedia functions not found\n");
		return 0;
	}
	device = device_create();
	rgba = device ? surface_create(device, SURFACE_RGBA, OUT_WIDTH, OUT_HEIGHT) : 0;
	/* The decoded surface: +0 type, +4 width, +8 height */
	mixer = rgba ? mixer_create(device, OUT_WIDTH, OUT_HEIGHT, aspect.bits, source[1], source[2],
		0, 0, 0, 0, 0, 0, MIXER_FEATURES, 0) : 0;

	encoder_config[0x00 / 4] = IDR_PERIOD;
	encoder_config[0x04 / 4] = 1;
	encoder_config[0x0c / 4] = 3;
	encoder_config[0x10 / 4] = 3;
	encoder_config[0x20 / 4] = BITRATE;
	encoder_config[0x80 / 4] = 1;
	encoder_config[0x84 / 4] = PROFILE_BASELINE;
	encoder_init[0x00 / 4] = OUT_WIDTH;
	encoder_init[0x04 / 4] = OUT_HEIGHT;
	encoder_init[0x14 / 4] = 30;
	encoder_init[0x18 / 4] = 1;
	encoder_init[0x20 / 4] = 1;
	encoder_init[0x30 / 4] = (u32)encoder_config;
	encoder = mixer ? encoder_create(device, ENCODER_H264, encoder_init, INPUT_RGBA, 4, 4, device) : 0;

	LOG("setup: source type %u %ux%u, device %08x rgba %08x mixer %08x encoder %08x\n",
		source[0], source[1], source[2], (u32)device, (u32)rgba, (u32)mixer, (u32)encoder);
	fflush(log_file);
	return encoder != 0;
}

/* Milliseconds since some point, for the debug log */
static u32 now_ms(void)
{
	struct timespec_qnx t;

	clock_gettime(CLOCK_MONOTONIC, &t);
	return (u32)t.tv_sec * 1000u + (u32)t.tv_nsec / 1000000u;
}

/*
 * Hands whatever the encoder has finished to the recording and/or the live stream.
 * Waits up to DRAIN_WAIT_US for the frame just fed: without the wait it only came out
 * with the next CarPlay frame, which on a still screen can be seconds later
 */
static void drain(int recording, int streaming)
{
	u32 bytes = 0;
	u32 waited = 0;
	int status;

	for (;;) {
		status = bits_available(encoder, &bytes, 0, 0);
		if (status != 0 || !bytes) {
			if (frames_out >= frames_fed || waited >= DRAIN_WAIT_US)
				break;
			usleep(DRAIN_POLL_US);
			waited += DRAIN_POLL_US;
			continue;
		}
		if (bytes > BITS_BUFFER) {
			LOG("frame %u: %u bytes is too big, dropped\n", frames, bytes);
			return;
		}
		status = get_bits(encoder, 0, &bytes, bits);
		if (status != 0) {
			LOG("frame %u: GetBits %d\n", frames, status);
			return;
		}
		if (recording && written_bytes + bytes <= ENC_MAX_BYTES) {
			fwrite(bits, 1, bytes, video_file);
			written_bytes += bytes;
		}
		if (streaming)
			cpkstream_frame(bits, bytes);
		if (frames < 5)
			LOG("frame %u: %u bytes\n", frames, bytes);
		frames_out++;
		DEBUG("O %u bytes %u fed %u out %u\n", now_ms(), bytes, frames_fed, frames_out);
		bytes = 0;
	}
	/* A frame that gave no output: count again from here instead of waiting on every frame */
	if (frames_out < frames_fed)
		frames_out = frames_fed;
}

static void stop(void)
{
	if (video_file)
		fclose(video_file);
	if (log_file)
		fclose(log_file);
	video_file = 0;
	log_file = 0;
}

/* The render thread and the repeater both use the encoder: one at a time */
static volatile int encoder_busy;

static void lock(void)
{
	while (__sync_lock_test_and_set(&encoder_busy, 1))
		usleep(500);
}

static void unlock(void)
{
	__sync_lock_release(&encoder_busy);
}

/* Encodes what is in rgba and hands the output on. Call with the lock held */
static void encode_and_send(int recording, int streaming)
{
	int status;

	/* Bit 0: IDR with SPS and PPS, for the first frame, a stream start or after a drop */
	picture_params[0] = frames == 0 || cpkstream_take_keyframe() ? 1 : 0;
	status = encoder_feed(encoder, rgba, 0, 0, picture_params);
	if (status != 0 || frames < 5)
		LOG("frame %u: FeedFrame %d\n", frames, status);
	if (status == 0)
		frames_fed++;
	drain(recording, streaming);
	frames++;
}

/*
 * While streaming, sends the last picture again when CarPlay has sent nothing for
 * REPEAT_US, at most cpkstream_repeat_limit() times after each CarPlay frame: the
 * cluster shows a frame only when the next one arrives
 */
static void *repeater(void *argument)
{
	u32 seen = frames_fed;
	u32 repeats = 0;

	(void)argument;
	for (;;) {
		usleep(REPEAT_US);
		if (!cpkstream_active() || broken) {
			seen = frames_fed;
			continue;
		}
		lock();
		if (frames_fed != seen) {
			/* A CarPlay frame came in: count the repeats after it again */
			repeats = 0;
		} else if (repeats < cpkstream_repeat_limit()) {
			DEBUG("P %u\n", now_ms());
			encode_and_send(0, 1);
			repeats++;
		}
		seen = frames_fed;
		unlock();
	}
	return 0;
}

static void start_repeater(void)
{
	static int started;
	int thread;

	if (started)
		return;
	started = 1;
	if (pthread_create(&thread, 0, repeater, 0) == 0)
		pthread_detach(thread);
}

/* Called with each decoded CarPlay surface, before it goes to the screen. Not exported */
__attribute__((visibility("hidden"))) void cpkenc_frame(void *surface)
{
	const u32 *source = (const u32 *)surface;
	struct nvm_rect src = { 0, 0, SOURCE_WIDTH, SOURCE_HEIGHT };
	struct nvm_rect dst = { PICTURE_X, 0, PICTURE_X + PICTURE_WIDTH, OUT_HEIGHT };
	struct nvm_primary primary = { 3, 0, surface, 0, 0, &src, &dst };
	int recording = access(ENC_TRIGGER, 0) == 0 && written_bytes < ENC_MAX_BYTES;
	int streaming = cpkstream_active();
	int status;

	lock();
	if (!recording && !streaming) {
		stop();
		unlock();
		return;
	}
	if (broken) {
		unlock();
		return;
	}
	if (!log_file) {
		log_file = fopen(ENC_LOG, "a");
		if (!log_file) {
			unlock();
			return;
		}
	}
	if (recording && !video_file) {
		video_file = fopen(ENC_VIDEO, "ab");
		if (!video_file)
			recording = 0;
	}
	if (!encoder && !setup(source)) {
		broken = 1;
		fflush(log_file);
		unlock();
		return;
	}
	if (streaming)
		start_repeater();
	debugging = access(DEBUG_TRIGGER, 0) == 0;
	DEBUG("R %u fed %u out %u\n", now_ms(), frames_fed, frames_out);
	if (source[1] < SOURCE_WIDTH || source[2] < SOURCE_HEIGHT) {
		src.x1 = (u16)source[1];
		src.y1 = (u16)source[2];
	}

	status = render_surface(mixer, rgba, 0, &primary, 0, 0, 0);
	if (status != 0 || frames < 5)
		LOG("frame %u: RenderSurface %d\n", frames, status);
	if (status == 0)
		encode_and_send(recording, streaming);
	if (video_file)
		fflush(video_file);
	fflush(log_file);
	unlock();
}
