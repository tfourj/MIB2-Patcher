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

/* cpkstream.c: live streaming to the cluster */
extern __attribute__((visibility("hidden"))) int cpkstream_active(void);
extern __attribute__((visibility("hidden"))) int cpkstream_take_keyframe(void);
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
static u32 written_bytes;
static u32 frames;
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
		fprintf(log_file, "setup: NvMedia functions not found\n");
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

	fprintf(log_file, "setup: source type %u %ux%u, device %08x rgba %08x mixer %08x encoder %08x\n",
		source[0], source[1], source[2], (u32)device, (u32)rgba, (u32)mixer, (u32)encoder);
	fflush(log_file);
	return encoder != 0;
}

/* Hands whatever the encoder has finished to the recording and/or the live stream */
static void drain(int recording, int streaming)
{
	u32 bytes = 0;
	int status;

	while ((status = bits_available(encoder, &bytes, 0, 0)) == 0 && bytes) {
		if (bytes > BITS_BUFFER) {
			fprintf(log_file, "frame %u: %u bytes is too big, dropped\n", frames, bytes);
			return;
		}
		status = get_bits(encoder, 0, &bytes, bits);
		if (status != 0) {
			fprintf(log_file, "frame %u: GetBits %d\n", frames, status);
			return;
		}
		if (recording && written_bytes + bytes <= ENC_MAX_BYTES) {
			fwrite(bits, 1, bytes, video_file);
			written_bytes += bytes;
		}
		if (streaming)
			cpkstream_frame(bits, bytes);
		if (frames < 5)
			fprintf(log_file, "frame %u: %u bytes\n", frames, bytes);
		bytes = 0;
	}
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

	if (!recording && !streaming) {
		stop();
		return;
	}
	if (broken)
		return;
	if (!log_file) {
		log_file = fopen(ENC_LOG, "a");
		if (!log_file)
			return;
	}
	if (recording && !video_file) {
		video_file = fopen(ENC_VIDEO, "ab");
		if (!video_file)
			recording = 0;
	}
	if (!encoder && !setup(source)) {
		broken = 1;
		fflush(log_file);
		return;
	}
	if (source[1] < SOURCE_WIDTH || source[2] < SOURCE_HEIGHT) {
		src.x1 = (u16)source[1];
		src.y1 = (u16)source[2];
	}

	status = render_surface(mixer, rgba, 0, &primary, 0, 0, 0);
	if (status != 0 || frames < 5)
		fprintf(log_file, "frame %u: RenderSurface %d\n", frames, status);
	if (status == 0) {
		/* Bit 0: IDR with SPS and PPS, for the first frame, a stream start or after a drop */
		picture_params[0] = frames == 0 || cpkstream_take_keyframe() ? 1 : 0;
		status = encoder_feed(encoder, rgba, 0, 0, picture_params);
		if (status != 0 || frames < 5)
			fprintf(log_file, "frame %u: FeedFrame %d\n", frames, status);
	}
	drain(recording, streaming);
	frames++;
	if (video_file)
		fflush(video_file);
	fflush(log_file);
}
