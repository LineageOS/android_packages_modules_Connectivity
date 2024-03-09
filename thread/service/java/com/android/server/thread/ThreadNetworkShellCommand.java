/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.thread;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.thread.IOperationReceiver;
import android.net.thread.ThreadNetworkException;
import android.os.Binder;
import android.os.Process;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.BasicShellCommandHandler;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Interprets and executes 'adb shell cmd thread_network [args]'.
 *
 * <p>To add new commands: - onCommand: Add a case "<command>" execute. Return a 0 if command
 * executed successfully. - onHelp: add a description string.
 *
 * <p>Permissions: currently root permission is required for some commands. Others will enforce the
 * corresponding API permissions.
 */
public class ThreadNetworkShellCommand extends BasicShellCommandHandler {
    private static final Duration SET_ENABLED_TIMEOUT = Duration.ofSeconds(2);

    // These don't require root access.
    private static final List<String> NON_PRIVILEGED_COMMANDS =
            List.of("help", "get-country-code", "enable", "disable");

    @NonNull private final ThreadNetworkControllerService mControllerService;
    @NonNull private final ThreadNetworkCountryCode mCountryCode;
    @Nullable private PrintWriter mOutputWriter;
    @Nullable private PrintWriter mErrorWriter;

    ThreadNetworkShellCommand(
            @NonNull ThreadNetworkControllerService controllerService,
            @NonNull ThreadNetworkCountryCode countryCode) {
        mControllerService = controllerService;
        mCountryCode = countryCode;
    }

    @VisibleForTesting
    public void setPrintWriters(PrintWriter outputWriter, PrintWriter errorWriter) {
        mOutputWriter = outputWriter;
        mErrorWriter = errorWriter;
    }

    private PrintWriter getOutputWriter() {
        return (mOutputWriter != null) ? mOutputWriter : getOutPrintWriter();
    }

    private PrintWriter getErrorWriter() {
        return (mErrorWriter != null) ? mErrorWriter : getErrPrintWriter();
    }

    @Override
    public int onCommand(String cmd) {
        // Treat no command as help command.
        if (TextUtils.isEmpty(cmd)) {
            cmd = "help";
        }

        final PrintWriter pw = getOutputWriter();
        final PrintWriter perr = getErrorWriter();

        // Explicit exclusion from root permission
        if (!NON_PRIVILEGED_COMMANDS.contains(cmd)) {
            final int uid = Binder.getCallingUid();

            if (uid != Process.ROOT_UID) {
                perr.println(
                        "Uid "
                                + uid
                                + " does not have access to "
                                + cmd
                                + " thread command "
                                + "(or such command doesn't exist)");
                return -1;
            }
        }

        switch (cmd) {
            case "enable":
                return setThreadEnabled(true);
            case "disable":
                return setThreadEnabled(false);
            case "force-country-code":
                boolean enabled;
                try {
                    enabled = getNextArgRequiredTrueOrFalse("enabled", "disabled");
                } catch (IllegalArgumentException e) {
                    perr.println("Invalid argument: " + e.getMessage());
                    return -1;
                }

                if (enabled) {
                    String countryCode = getNextArgRequired();
                    if (!ThreadNetworkCountryCode.isValidCountryCode(countryCode)) {
                        perr.println(
                                "Invalid argument: Country code must be a 2-Character"
                                        + " string. But got country code "
                                        + countryCode
                                        + " instead");
                        return -1;
                    }
                    mCountryCode.setOverrideCountryCode(countryCode);
                    pw.println("Set Thread country code: " + countryCode);

                } else {
                    mCountryCode.clearOverrideCountryCode();
                }
                return 0;
            case "get-country-code":
                pw.println("Thread country code = " + mCountryCode.getCountryCode());
                return 0;
            default:
                return handleDefaultCommands(cmd);
        }
    }

    private int setThreadEnabled(boolean enabled) {
        final PrintWriter perr = getErrorWriter();

        CompletableFuture<Void> setEnabledFuture = new CompletableFuture<>();
        mControllerService.setEnabled(
                enabled,
                new IOperationReceiver.Stub() {
                    @Override
                    public void onSuccess() {
                        setEnabledFuture.complete(null);
                    }

                    @Override
                    public void onError(int errorCode, String errorMessage) {
                        setEnabledFuture.completeExceptionally(
                                new ThreadNetworkException(errorCode, errorMessage));
                    }
                });

        try {
            setEnabledFuture.get(SET_ENABLED_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            perr.println("Failed: " + e.getMessage());
        } catch (ExecutionException e) {
            perr.println("Failed: " + e.getCause().getMessage());
        } catch (TimeoutException e) {
            perr.println("Failed: command timeout for " + SET_ENABLED_TIMEOUT);
        }

        return -1;
    }

    private static boolean argTrueOrFalse(String arg, String trueString, String falseString) {
        if (trueString.equals(arg)) {
            return true;
        } else if (falseString.equals(arg)) {
            return false;
        } else {
            throw new IllegalArgumentException(
                    "Expected '"
                            + trueString
                            + "' or '"
                            + falseString
                            + "' as next arg but got '"
                            + arg
                            + "'");
        }
    }

    private boolean getNextArgRequiredTrueOrFalse(String trueString, String falseString) {
        String nextArg = getNextArgRequired();
        return argTrueOrFalse(nextArg, trueString, falseString);
    }

    private void onHelpNonPrivileged(PrintWriter pw) {
        pw.println("  enable");
        pw.println("    Enables Thread radio");
        pw.println("  disable");
        pw.println("    Disables Thread radio");
        pw.println("  get-country-code");
        pw.println("    Gets country code as a two-letter string");
    }

    private void onHelpPrivileged(PrintWriter pw) {
        pw.println("  force-country-code enabled <two-letter code> | disabled ");
        pw.println("    Sets country code to <two-letter code> or left for normal value");
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutputWriter();
        pw.println("Thread network commands:");
        pw.println("  help or -h");
        pw.println("    Print this help text.");
        onHelpNonPrivileged(pw);
        if (Binder.getCallingUid() == Process.ROOT_UID) {
            onHelpPrivileged(pw);
        }
        pw.println();
    }
}
