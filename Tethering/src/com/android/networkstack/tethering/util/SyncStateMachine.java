/**
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

package com.android.networkstack.tethering.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;

import com.android.internal.util.State;

import java.util.List;

/**
 * An implementation of a state machine, meant to be called synchronously.
 *
 * This class implements a finite state automaton based on the same State
 * class as StateMachine.
 * All methods of this class must be called on only one thread.
 */
public class SyncStateMachine {
    @NonNull private final String mName;
    @NonNull private final Thread mMyThread;
    private final boolean mDbg;

    // mCurrentState is the current state. mDestState is the target state that mCurrentState will
    // transition to. The value of mDestState can be changed when a state processes a message and
    // calls #transitionTo, but it cannot be changed during the state transition. When the state
    // transition is complete, mDestState will be set to mCurrentState. Both mCurrentState and
    // mDestState only be null before state machine starts and must only be touched on mMyThread.
    @Nullable private State mCurrentState;
    @Nullable private State mDestState;

    /**
     * A information class about a state and its parent. Used to maintain the state hierarchy.
     */
    public static class StateInfo {
        /** The state who owns this StateInfo. */
        public final State state;
        /** The parent state. */
        public final State parent;
        // True when the state has been entered and on the stack.
        private boolean mActive;

        public StateInfo(@NonNull final State child, @Nullable final State parent) {
            this.state = child;
            this.parent = parent;
        }
    }

    /**
     * The constructor.
     *
     * @param name of this machine.
     * @param thread the running thread of this machine. It must either be the thread on which this
     * constructor is called, or a thread that is not started yet.
     */
    public SyncStateMachine(@NonNull final String name, @NonNull final Thread thread) {
        this(name, thread, false /* debug */);
    }

    /**
     * The constructor.
     *
     * @param name of this machine.
     * @param thread the running thread of this machine. It must either be the thread on which this
     * constructor is called, or a thread that is not started yet.
     * @param dbg whether to print debug logs.
     */
    public SyncStateMachine(@NonNull final String name, @NonNull final Thread thread,
            final boolean dbg) {
        mMyThread = thread;
        // Machine can either be setup from machine thread or before machine thread started.
        ensureCorrectOrNotStartedThread();

        mName = name;
        mDbg = dbg;
    }

    /**
     * Add all of states to the state machine. Different StateInfos which have same state but have
     * different parents are not allowed. A state can not have multiple parent states.
     * This can only be called once either from mMyThread or before mMyThread started.
     */
    public final void addAllStates(@NonNull final List<StateInfo> stateInfos) {
        ensureCorrectOrNotStartedThread();

        if (mCurrentState != null) {
            throw new IllegalStateException("State only can be added before started");
        }
    }

    /**
     * Start the state machine. The initial state can't be child state.
     */
    public final void start(@NonNull final State initialState) {
        ensureCorrectThread();

        mCurrentState = initialState;
        mDestState = initialState;
    }

    /**
     * Process the message synchronously then perform state transition.
     */
    public final void processMessage(int what, int arg1, int arg2, @Nullable Object obj) {
        ensureCorrectThread();
    }

    /**
     * Transition to destination state. Upon returning from processMessage the automaton will
     * transition to the given destination state.
     *
     * This function can NOT be called inside the State enter and exit function. The transition
     * target is always defined and can never be changed mid-way of state transition.
     *
     * @param destState will be the state to transition to.
     */
    public final void transitionTo(@NonNull final State destState) {
        if (mDbg) Log.d(mName, "transitionTo " + destState);
        ensureCorrectThread();

        if (mDestState == mCurrentState) {
            mDestState = destState;
        } else {
            throw new IllegalStateException("Destination already specified");
        }
    }

    private void ensureCorrectThread() {
        if (!mMyThread.equals(Thread.currentThread())) {
            throw new IllegalStateException("Called from wrong thread");
        }
    }

    private void ensureCorrectOrNotStartedThread() {
        if (!mMyThread.isAlive()) return;

        ensureCorrectThread();
    }
}
