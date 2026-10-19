/* llm-reboot [bootloader|recovery|...]: reboot, passing the argument to the
 * bootloader (busybox reboot can't). "llm-reboot bootloader" -> fastboot mode. */
#include <linux/reboot.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(int argc, char **argv)
{
	sync();
	if (argc > 1)
		syscall(SYS_reboot, LINUX_REBOOT_MAGIC1, LINUX_REBOOT_MAGIC2,
			LINUX_REBOOT_CMD_RESTART2, argv[1]);
	else
		syscall(SYS_reboot, LINUX_REBOOT_MAGIC1, LINUX_REBOOT_MAGIC2,
			LINUX_REBOOT_CMD_RESTART, NULL);
	perror("reboot");
	return 1;
}
