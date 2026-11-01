/*
 * CARPLAY2KOMBI test writer: sends an MPEG-TS file to the cluster's MOST channel
 * in real time, the way displaymanager's encoder does.
 *
 * There is no QNX SDK to link a program, so this is a library whose constructor
 * does the work inside any host program and then exits, for example:
 *   CPK_INJECT=/ramdisk/cpk/test.ts LD_PRELOAD=/ramdisk/cpk/libcpkinject.so /bin/ls
 *
 * The driver takes whole blocks of 64 TS packets (12032 bytes) per write. Each
 * block is sent when the stream's clock (the first PCR in the block) is due,
 * slightly early so the decoder's buffer stays ahead. Logs to /ramdisk/cpk/inject.log.
 */

typedef unsigned char u8;
typedef unsigned int u32;
typedef unsigned long long u64;
typedef struct FILE FILE;

struct timespec_qnx {
	long tv_sec;
	long tv_nsec;
};

extern char *getenv(const char *name);
extern int atoi(const char *text);
extern int open(const char *path, int flags, ...);
extern int read(int fd, void *data, u32 size);
extern int write(int fd, const void *data, u32 size);
extern int close(int fd);
extern int lseek(int fd, int offset, int whence);
extern int usleep(u32 microseconds);
extern int clock_gettime(int clock, struct timespec_qnx *now);
extern void _exit(int status);
extern FILE *fopen(const char *path, const char *mode);
extern int fprintf(FILE *file, const char *format, ...);
extern int fclose(FILE *file);

#define DEVICE "/dev/mlb/isoTX2"
#define LOG_FILE "/ramdisk/cpk/inject.log"
#define PACKET 188
#define BLOCK (64 * PACKET)
#define CLOCK_REALTIME 0
/* Send each block this far ahead of its PCR */
#define LEAD_US 300000

static u8 block[BLOCK];

static u64 now_us(void)
{
	struct timespec_qnx t;

	clock_gettime(CLOCK_REALTIME, &t);
	return (u64)t.tv_sec * 1000000u + (u64)t.tv_nsec / 1000u;
}

/* First PCR in the block, in 90 kHz ticks, or -1 */
static long long first_pcr(const u8 *data)
{
	int i;

	for (i = 0; i < BLOCK; i += PACKET) {
		const u8 *p = data + i;

		if (p[0] != 0x47 || !(p[3] & 0x20) || p[4] < 7 || !(p[5] & 0x10))
			continue;
		return ((long long)p[6] << 25) | ((long long)p[7] << 17) | ((long long)p[8] << 9) |
			((long long)p[9] << 1) | (p[10] >> 7);
	}
	return -1;
}

/*
 * O_WRONLY like displaymanager. CPK_DEVICE can name an existing file instead,
 * to check the pacing without the cluster
 */
static void inject(const char *path, const char *device, int loops, FILE *log)
{
	int in, out, loop;
	u32 blocks = 0, failed = 0;

	in = open(path, 0);
	out = open(device, 1);
	fprintf(log, "file %s fd %d, %s fd %d, loops %d\n", path, in, device, out, loops);
	if (in < 0 || out < 0)
		return;

	for (loop = 0; loop < loops; loop++) {
		long long pcr0 = -1;
		u64 start = now_us();

		lseek(in, 0, 0);
		while (read(in, block, BLOCK) == BLOCK) {
			long long pcr = first_pcr(block);

			if (pcr >= 0 && pcr0 < 0)
				pcr0 = pcr;
			if (pcr >= 0) {
				u64 due = start + (u64)(pcr - pcr0) * 100u / 9u;
				u64 now = now_us();

				if (due > now + LEAD_US)
					usleep((u32)(due - now - LEAD_US));
			}
			if (write(out, block, BLOCK) == BLOCK)
				blocks++;
			else
				failed++;
		}
		fprintf(log, "loop %d done after %u ms\n", loop, (u32)((now_us() - start) / 1000u));
	}
	fprintf(log, "blocks written %u, failed %u\n", blocks, failed);
	close(out);
	close(in);
}

__attribute__((constructor)) static void run(void)
{
	const char *path = getenv("CPK_INJECT");
	const char *loops = getenv("CPK_LOOPS");
	const char *device = getenv("CPK_DEVICE");
	FILE *log;

	if (!path)
		return;
	log = fopen(LOG_FILE, "w");
	if (!log)
		_exit(1);
	inject(path, device ? device : DEVICE, loops ? atoi(loops) : 1, log);
	fclose(log);
	_exit(0);
}
