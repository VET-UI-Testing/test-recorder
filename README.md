# TestRecorder

This repository contains the code of our on-computer test recorder that works with [TOLLER](https://github.com/TOLLER-Android/main). The program records UI traces (that consist of UI hierarchies interleaving with actions) to reflect the testing process. It does not matter who operates the target app--both humans and automated tools can record with this program.

## Building

You can download our prebuilt binary from [here](https://drive.google.com/drive/folders/1bYFwOuy4s3Pn3xRw7X5qIlphCBe8mDYe).

To build by yourself, import this repo with some IDE (e.g., JetBrains IntelliJ Idea) and follow its instructions to generate a JAR archive. All dependent libraries are available in `lib/`.

You should have `TestRecorder.jar` in the root directory of this repo after this step. If you want to record traces during testing using [TOLLER's experiment framework](https://github.com/TOLLER-Android/main), place this repo in the `test-recorder` folder in the root directory of TOLLER's repo.

## Basic Usages

To quickly start recording:

```bash
$ CTRL_PORT={CTRL_PORT} SKIP_MINICAP=1 java -jar TestRecorder.jar {OUT_DIR}
```

Replace `CTRL_PORT` with the ADB-forwarded port number to reach TOLLER (see [here](https://github.com/TOLLER-Android/main/blob/main/USAGES.md) for how). Also replace `OUT_DIR` with the path to the directory where you want to store UI hierarchies. As you operate on the target app, you will see many JSON files in `OUT_DIR`, each consists of a UI hierarchy and the action observed on it.

Note that the aforementioned command does not record screenshots. If you need screenshots, use the following command:

```bash
$ CTRL_PORT={CTRL_PORT} MINICAP_PORT={MINICAP_PORT} java -jar TestRecorder.jar {OUT_DIR} {SCREEN_OUT_DIR}
```

Replace `MINICAP_PORT` with the ADB-forwarded port number to reach Minicap (see [here](https://github.com/VET-UI-Testing/minicap) for how). Also replace `SCREEN_OUT_DIR` with the path to the directory where you want to store screenshots.

## Extended Usages

The test recorder accepts a set of environment variables as configs:

* `XPATHS_TO_KILL` specifies which UI elements to disable and which UI screens to restart the target app on during recording. You can generate this value with:

  ```bash
  $ python3 get-source-xpath.py JSON_UI_1 [JSON_UI_2 ...]
  ```
  Each `JSON_UI` argument corresponds to the path to a JSON file from the test recorder from a previous run.
* `RETAIN_CRASH_HANDLER` specifies whether the app's default crash handler should be preserved. Set this to `1` to activate this feature.
* `APP_RESTART_COMMAND` specifies the command to execute when the recorder decides to restart the target app. Note that this command will be executed on the computer instead of the test device.
* `WATCHDOG_INTV` specifies the interval between two progress checks performed by the watchdog. The watchdog will restart the test device after 3 consecutive failed checks (i.e., when the recorder has not received any new action). Unset this environment variable to disable this feature.
* `DEV_RESTART_COMMAND` specifies the command to execute when the recorder decides to restart the test device. Only effective when `WATCHDOG_INTV` is set. Note that this command will be executed on the computer instead of the test device.
* `DONT_KILL_AFTER` specifies when the watchdog should stop after recording starts. Only effective when `WATCHDOG_INTV` is set.