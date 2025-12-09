/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.ctdemo;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Trace;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    Handler handler;
    int cookie;

    private void connectTo(String urlString, TextView textView) {
        int myCookie = cookie++;
        URL url = null;
        long startTime = System.nanoTime();
        Trace.beginAsyncSection("connectTo", myCookie);
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            Trace.endAsyncSection("connectTo", myCookie);
            handler.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            textView.setText(e.toString());
                        }
                    });
            return;
        }
        HttpURLConnection urlConnection = null;
        try {
            urlConnection = (HttpURLConnection) url.openConnection();
        } catch (IOException e) {
            Trace.endAsyncSection("connectTo", myCookie);
            long estimatedTime = (System.nanoTime() - startTime) / 1000;
            handler.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            textView.setText(
                                    "Failure:" + e.getMessage() + " in " + estimatedTime + " us");
                        }
                    });
            return;
        }
        try {
            InputStream in = new BufferedInputStream(urlConnection.getInputStream());
            in.read();
        } catch (IOException e) {
            Trace.endAsyncSection("connectTo", myCookie);
            long estimatedTime = (System.nanoTime() - startTime) / 1000;
            handler.post(
                    new Runnable() {
                        @Override
                        public void run() {
                            textView.setText(
                                    "Failure: " + e.getMessage() + " in " + estimatedTime + " us");
                        }
                    });
            return;
        }
        Trace.endAsyncSection("connectTo", myCookie);
        long estimatedTime = (System.nanoTime() - startTime) / 1000;
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        textView.setText("Success in " + estimatedTime + " us");
                    }
                });
        urlConnection.disconnect();
    }

    private void checkLogList(TextView textView) {
        StringBuilder builder = new StringBuilder();
        Path root = Paths.get("/data/misc/keychain/ct/");
        try {
            for (Path version : Files.newDirectoryStream(root, "v*")) {
                if (Files.isDirectory(version)) {
                    for (Path dir : Files.newDirectoryStream(version)) {
                        if (!dir.endsWith("current")) {
                            builder.append(dir.toString() + "\n");
                        }
                    }
                }
            }
        } catch (IOException e) {
            builder = new StringBuilder();
            builder.append(e.toString());
        }
        final String text = builder.toString();
        handler.post(
                new Runnable() {
                    @Override
                    public void run() {
                        textView.setText(text);
                    }
                });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        handler = new Handler(Looper.getMainLooper());
        Button buttonWikipedia = (Button) findViewById(R.id.button_wikipedia);
        Button buttonValid = (Button) findViewById(R.id.button_valid_badssl);
        Button buttonNoSCT = (Button) findViewById(R.id.button_no_sct_badssl);
        Button buttonCheckLogList = (Button) findViewById(R.id.button_check_log_list);
        final TextView textView = (TextView) findViewById(R.id.textView);
        buttonWikipedia.setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        executor.execute(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        connectTo("https://www.android.com", textView);
                                    }
                                });
                    }
                });
        buttonValid.setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        executor.execute(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        connectTo("https://sha256.badssl.com/", textView);
                                    }
                                });
                    }
                });
        buttonNoSCT.setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        executor.execute(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        connectTo("https://no-sct.badssl.com/", textView);
                                    }
                                });
                    }
                });
        buttonCheckLogList.setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        executor.execute(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        checkLogList(textView);
                                    }
                                });
                    }
                });
    }
}
