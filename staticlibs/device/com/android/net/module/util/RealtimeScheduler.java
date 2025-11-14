/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.net.module.util;

import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;

import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.util.CloseGuard;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.PriorityQueue;

/**
 * Represents a realtime scheduler object used for scheduling tasks with precise delays.
 * Compared to {@link Handler#postDelayed}, this class offers enhanced accuracy for delayed
 * callbacks by accounting for periods when the device is in deep sleep.
 *
 *  <p> This class is designed for use exclusively from the handler thread.
 *
 * **Usage Examples:**
 *
 * ** Scheduling recurring tasks with the same RealtimeScheduler **
 *
 * ```java
 * // Create a RealtimeScheduler
 * final RealtimeScheduler scheduler = new RealtimeScheduler(handler);
 *
 * // Schedule a new task with a delay.
 * scheduler.postDelayed(() -> taskToExecute(), delayTime);
 *
 * // Once the delay has elapsed, and the task is running, schedule another task.
 * scheduler.postDelayed(() -> anotherTaskToExecute(), anotherDelayTime);
 *
 * // Remember to close the RealtimeScheduler after all tasks have finished running.
 * scheduler.close();
 * ```
 */
public class RealtimeScheduler {
    private static final String TAG = RealtimeScheduler.class.getSimpleName();
    // EVENT_ERROR may be generated even if not specified, as per its javadoc.
    private static final int FD_EVENTS = EVENT_INPUT | EVENT_ERROR;
    private final CloseGuard mGuard = new CloseGuard();
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final MessageQueue mQueue;
    @NonNull
    private final ParcelFileDescriptor mParcelFileDescriptor;
    private final int mFdInt;

    private final PriorityQueue<Task> mTaskQueue;

    /**
     * An abstract class for defining tasks that can be executed using a {@link Handler}.
     */
    private abstract static class Task implements Comparable<Task> {
        private final long mRunTimeMs;
        private final long mCreatedTimeNs = SystemClock.elapsedRealtimeNanos();

        /**
         * create a task with a run time
         */
        Task(long runTimeMs) {
            mRunTimeMs = runTimeMs;
        }

        /**
         * Executes the task using the provided {@link Handler}.
         *
         * @param handler The {@link Handler} to use for executing the task.
         */
        abstract void post(Handler handler);

        @Override
        public int compareTo(@NonNull Task o) {
            if (mRunTimeMs != o.mRunTimeMs) {
                return Long.compare(mRunTimeMs, o.mRunTimeMs);
            }
            return Long.compare(mCreatedTimeNs, o.mCreatedTimeNs);
        }

        /**
         * Returns the run time of the task.
         */
        public long getRunTimeMs() {
            return mRunTimeMs;
        }
    }

    /**
     * A task that sends a {@link Message} using a {@link Handler}.
     */
    private static class MessageTask extends Task {
        private final Message mMessage;

        MessageTask(Message message, long runTimeMs) {
            super(runTimeMs);
            mMessage = message;
        }

        /**
         * Sends the {@link Message} using the provided {@link Handler}.
         *
         * @param handler The {@link Handler} to use for sending the message.
         */
        @Override
        public void post(Handler handler) {
            handler.sendMessage(mMessage);
        }
    }

    /**
     * A task that posts a {@link Runnable} to a {@link Handler}.
     */
    private static class RunnableTask extends Task {
        private final Runnable mRunnable;

        RunnableTask(Runnable runnable, long runTimeMs) {
            super(runTimeMs);
            mRunnable = runnable;
        }

        /**
         * Posts the {@link Runnable} to the provided {@link Handler}.
         *
         * @param handler The {@link Handler} to use for posting the runnable.
         */
        @Override
        public void post(Handler handler) {
            handler.post(mRunnable);
        }
    }

    /**
     * The RealtimeScheduler constructor
     *
     * Note: The constructor is currently safe to call on another thread because it only sets final
     * members and registers the event to be called on the handler.
     */
    public RealtimeScheduler(@NonNull Handler handler) {
        mFdInt = TimerFdUtils.createTimerFileDescriptor();
        mParcelFileDescriptor = ParcelFileDescriptor.adoptFd(mFdInt);
        mHandler = handler;
        mQueue = handler.getLooper().getQueue();
        mTaskQueue = new PriorityQueue<>();
        registerFdEventListener();

        mGuard.open("close");
    }

    private boolean enqueueTask(@NonNull Task task, long delayMs) {
        ensureRunningOnCorrectThread();
        if (delayMs <= 0L) {
            task.post(mHandler);
            return true;
        }
        if (mTaskQueue.isEmpty() || task.compareTo(mTaskQueue.peek()) < 0) {
            if (!TimerFdUtils.setExpirationTime(mFdInt, delayMs)) {
                return false;
            }
        }
        mTaskQueue.add(task);
        return true;
    }

    /**
     * Set a runnable to be executed after a specified delay.
     *
     * If delayMs is less than or equal to 0, the runnable will be executed immediately.
     *
     * @param runnable the runnable to be executed
     * @param delayMs the delay time in milliseconds
     * @return true if the task is scheduled successfully, false otherwise.
     */
    public boolean postDelayed(@NonNull Runnable runnable, long delayMs) {
        return enqueueTask(new RunnableTask(runnable, SystemClock.elapsedRealtime() + delayMs),
                delayMs);
    }

    /**
     * Remove a scheduled runnable.
     *
     * @param runnable the runnable to be removed
     */
    public void removeDelayedRunnable(@NonNull Runnable runnable) {
        ensureRunningOnCorrectThread();
        mTaskQueue.removeIf(task -> task instanceof RunnableTask
                && ((RunnableTask) task).mRunnable == runnable);
    }

    /**
     * Set a message to be sent after a specified delay.
     *
     * If delayMs is less than or equal to 0, the message will be sent immediately.
     *
     * @param what Value to assign to the returned Message.what field.
     * @param arg1 Value to assign to the returned Message.arg1 field.
     * @param arg2 Value to assign to the returned Message.arg2 field.
     * @param obj Value to assign to the returned Message.obj field.
     * @param delayMs the delay time in milliseconds
     * @return true if the message is scheduled successfully, false otherwise.
     */
    public boolean sendDelayedMessage(int what, int arg1, int arg2, @Nullable Object obj,
            long delayMs) {
        final Message msg = mHandler.obtainMessage(what, arg1, arg2, obj);
        return enqueueTask(new MessageTask(msg, SystemClock.elapsedRealtime() + delayMs), delayMs);
    }

    private static boolean isMessageTask(Task task, int what) {
        if (task instanceof MessageTask && ((MessageTask) task).mMessage.what == what) {
            return true;
        }
        return false;
    }

    /**
     * Remove a scheduled message.
     *
     * @param what the message to be removed
     */
    public void removeDelayedMessage(int what) {
        ensureRunningOnCorrectThread();
        mTaskQueue.removeIf(task -> isMessageTask(task, what));
    }

    /**
     * Check if there is a scheduled message.
     *
     * @param what the message to be checked
     * @return true if there is a target message, false otherwise.
     */
    public boolean hasDelayedMessage(int what) {
        ensureRunningOnCorrectThread();

        for (Task task : mTaskQueue) {
            if (isMessageTask(task, what)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Close the RealtimeScheduler. This implementation closes the underlying
     * OS resources allocated to represent this stream.
     */
    public void close() {
        ensureRunningOnCorrectThread();
        unregisterAndDestroyFd();
    }

    private void registerFdEventListener() {
        mQueue.addOnFileDescriptorEventListener(
                mParcelFileDescriptor.getFileDescriptor(),
                FD_EVENTS,
                (fd, events) -> {
                    if (!isRunning()) {
                        return 0;
                    }
                    if ((events & EVENT_ERROR) != 0) {
                        Log.wtf(TAG, "Got EVENT_ERROR from FileDescriptorEventListener.");
                        return 0;
                    }
                    if ((events & EVENT_INPUT) != 0) {
                        handleExpiration();
                    }
                    return FD_EVENTS;
                });
    }

    private boolean isRunning() {
        return mParcelFileDescriptor.getFileDescriptor().valid();
    }

    private void handleExpiration() {
        // The data from the FileDescriptor must be read after the timer expires. Otherwise,
        // expiration callbacks will continue to be sent, notifying of unread data. The content(the
        // number of expirations) can be ignored, as the callback is the only item of interest.
        // Refer to https://man7.org/linux/man-pages/man2/timerfd_create.2.html
        // read(2)
        //         If the timer has already expired one or more times since
        //         its settings were last modified using timerfd_settime(),
        //         or since the last successful read(2), then the buffer
        //         given to read(2) returns an unsigned 8-byte integer
        //         (uint64_t) containing the number of expirations that have
        //         occurred.  (The returned value is in host byte order—that
        //         is, the native byte order for integers on the host
        //         machine.)
        final byte[] readBuffer = new byte[8];
        try {
            Os.read(mParcelFileDescriptor.getFileDescriptor(), readBuffer, 0, readBuffer.length);
        } catch (IOException | ErrnoException exception) {
            Log.wtf(TAG, "Read FileDescriptor failed. ", exception);
        }

        long currentTimeMs = SystemClock.elapsedRealtime();
        while (!mTaskQueue.isEmpty()) {
            final Task task = mTaskQueue.peek();
            currentTimeMs = SystemClock.elapsedRealtime();
            if (currentTimeMs < task.getRunTimeMs()) {
                break;
            }
            task.post(mHandler);
            mTaskQueue.poll();
        }

        if (!mTaskQueue.isEmpty()) {
            // Using currentTimeMs ensures that the calculated expiration time
            // is always positive.
            if (!TimerFdUtils.setExpirationTime(mFdInt,
                    mTaskQueue.peek().getRunTimeMs() - currentTimeMs)) {
                // If setting the expiration time fails, clear the task queue.
                Log.wtf(TAG, "Failed to set expiration time");
                mTaskQueue.clear();
            }
        }
    }

    private void unregisterAndDestroyFd() {
        if (mGuard != null) {
            mGuard.close();
        }

        mQueue.removeOnFileDescriptorEventListener(mParcelFileDescriptor.getFileDescriptor());
        try {
            mParcelFileDescriptor.close();
        } catch (IOException exception) {
            Log.e(TAG, "close ParcelFileDescriptor failed. ", exception);
        }
    }

    private void ensureRunningOnCorrectThread() {
        if (mHandler.getLooper() != Looper.myLooper()) {
            throw new IllegalStateException(
                    "Not running on Handler thread: " + Thread.currentThread().getName());
        }
    }

    @SuppressWarnings("Finalize")
    @Override
    protected void finalize() throws Throwable {
        if (mGuard != null) {
            mGuard.warnIfOpen();
        }
        super.finalize();
    }
}
