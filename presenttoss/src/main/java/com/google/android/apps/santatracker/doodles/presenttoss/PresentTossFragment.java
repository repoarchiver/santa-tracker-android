/*
 * Copyright 2019. Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.apps.santatracker.doodles.presenttoss;

import static com.google.android.apps.santatracker.doodles.shared.logging.DoodleLogEvent.PRESENT_TOSS_GAME_TYPE;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.google.android.apps.santatracker.doodles.presenttoss.PresentTossModel.State;
import com.google.android.apps.santatracker.doodles.shared.AndroidUtils;
import com.google.android.apps.santatracker.doodles.shared.EventBus;
import com.google.android.apps.santatracker.doodles.shared.EventBus.EventBusListener;
import com.google.android.apps.santatracker.doodles.shared.GameType;
import com.google.android.apps.santatracker.doodles.shared.HistoryManager;
import com.google.android.apps.santatracker.doodles.shared.sound.SoundManager;
import com.google.android.apps.santatracker.doodles.shared.views.GameFragment;
import com.google.android.apps.santatracker.doodles.shared.views.PauseView;
import com.google.android.apps.santatracker.doodles.shared.views.ScoreView;
import com.google.android.apps.santatracker.util.SantaLog;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Activity for the ported water polo game. Manages input & threads, delegates to PresentTossModel &
 * WaterPoloView for the rest.
 */
public class PresentTossFragment extends GameFragment implements EventBusListener {
    private static final String TAG = PresentTossFragment.class.getSimpleName();

    private PresentTossView gameView;
    private final AtomicReference<PresentTossModel> modelRef = new AtomicReference<>();
    private WaterPoloGestureDetector gestureDetector;
    private EventBus eventBus;
    private boolean mIsGameOver = false;

    @Override
    protected ScoreView getScoreView() {
        PresentTossScoreView scoreView = new PresentTossScoreView(context, this);
        scoreView.setLogger(logger);
        scoreView.setListener(levelFinishedListener);
        scoreView.setModel(modelRef.get());
        return scoreView;
    }

    @Override
    protected PauseView getPauseView() {
        PresentTossPauseView pauseView = new PresentTossPauseView(context);
        pauseView.setLogger(logger);
        pauseView.setListener(gamePausedListener);

        return pauseView;
    }

    @Override
    public void update(float deltaMs) {
        final PresentTossModel model = modelRef.get();
        if (!isPaused && model != null) {
            model.update((long) deltaMs);
        }
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (context == null) {
            return null;
        }
        eventBus = EventBus.getInstance();
        historyManager =
                new HistoryManager(
                        context,
                        new HistoryManager.HistoryListener() {
                            @Override
                            public void onFinishedLoading() {}

                            @Override
                            public void onFinishedSaving() {}
                        });

        wrapper = new FrameLayout(context);
        return wrapper;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        loadGame();
    }

    @Override
    protected void firstPassLoadOnUiThread() {
        wrapper.setOnTouchListener(
                new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        return onTouchEvent(event);
                    }
                });
        final FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT);
        // Add game view & finish view.
        gameView = new PresentTossView(context);
        wrapper.addView(gameView, 0, lp);

        pauseView = getPauseView();
        wrapper.addView(pauseView, 1);
        scoreView = getScoreView();
        wrapper.addView(scoreView, 1);
    }

    @Override
    protected void secondPassLoadOnBackgroundThread() {
        super.secondPassLoadOnBackgroundThread();
        eventBus.register(this);
        PresentTossModel model =
                new PresentTossModel(context.getResources(), context.getApplicationContext());
        modelRef.set(model);
        gameView.setModel(model);
        if (scoreView instanceof PresentTossScoreView) {
            ((PresentTossScoreView) scoreView).setModel(model);
        }
    }

    @Override
    protected void finalPassLoadOnUiThread() {
        gestureDetector =
                new WaterPoloGestureDetector() {
                    @Override
                    public void onPan(float radians) {
                        modelRef.get().onFling(radians);
                    }
                };

        soundManager = SoundManager.getInstance();
        loadSounds();

        onFinishedLoading();
        startHandlers();
    }

    @Override
    protected void replay() {
        super.replay();
        modelRef.get().reset(false);
    }

    @Override
    protected void onDestroyHelper() {
        if (gameView != null) {
            gameView.setModel(null);
        }
        modelRef.set(null);
    }

    @Override
    public void onEventReceived(int type, Object data) {
        if (isDestroyed) {
            return;
        }
        final PresentTossModel model = modelRef.get();

        if (type == EventBus.SCORE_CHANGED) {
            final Integer score = (Integer) data;
            getActivity()
                    .runOnUiThread(
                            new Runnable() {
                                @Override
                                public void run() {
                                    scoreView.updateCurrentScore(
                                            AndroidUtils.getText(
                                                    context.getResources(),
                                                    com.google
                                                            .android
                                                            .apps
                                                            .santatracker
                                                            .common
                                                            .R
                                                            .string
                                                            .waterpolo_score,
                                                    score),
                                            true);
                                }
                            });
        } else if (type == EventBus.PLAY_SOUND && soundManager != null) {
            int resId = (int) data;
            soundManager.play(resId);
        } else if (type == EventBus.PAUSE_SOUND && soundManager != null) {
            int resId = (int) data;
            soundManager.pause(resId);
        } else if (type == EventBus.MUTE_SOUNDS && soundManager != null) {
            boolean shouldMute = (boolean) data;
            if (shouldMute) {
                soundManager.mute();
            } else {
                soundManager.unmute();
            }
        } else if (type == EventBus.GAME_STATE_CHANGED) {
            mIsGameOver = false;
            PresentTossModel.State state = (PresentTossModel.State) data;
            if (state == State.WAITING) {
                getActivity()
                        .runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        hideTitle();
                                        pauseView.showPauseButton();
                                    }
                                });
            } else if (state == State.GAME_OVER) {
                mIsGameOver = true;
                wrapper.postDelayed(
                        new Runnable() {
                            @Override
                            public void run() {
                                final PresentTossModel model = modelRef.get();
                                if (model == null) {
                                    return;
                                }
                                final int currentScore = model.score;
                                boolean shouldSave = false;
                                Integer bestStarCount =
                                        historyManager.getBestStarCount(GameType.WATER_POLO);
                                int starCount = getStarCount();
                                if (bestStarCount == null || bestStarCount < starCount) {
                                    historyManager.setBestStarCount(GameType.WATER_POLO, starCount);
                                    shouldSave = true;
                                }
                                Double temp = historyManager.getBestScore(GameType.WATER_POLO);
                                int bestScore = temp == null ? -1 : (int) Math.round(temp);
                                if (currentScore > bestScore) {
                                    bestScore = currentScore;
                                    historyManager.setBestScore(GameType.WATER_POLO, currentScore);
                                    shouldSave = true;
                                }
                                if (shouldSave) {
                                    historyManager.save();
                                }

                                final int finalStarCount = starCount;
                                final int finalBestScore = bestScore;
                                getActivity()
                                        .runOnUiThread(
                                                new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        for (int i = 0; i < finalStarCount; i++) {
                                                            scoreView.addStar();
                                                        }
                                                        pauseView.hidePauseButton();
                                                        scoreView.updateBestScore(
                                                                AndroidUtils.getText(
                                                                        context.getResources(),
                                                                        com.google
                                                                                .android
                                                                                .apps
                                                                                .santatracker
                                                                                .common
                                                                                .R
                                                                                .string
                                                                                .waterpolo_score,
                                                                        finalBestScore));
                                                        scoreView.setShareDrawable(
                                                                getShareImageDrawable(
                                                                        finalStarCount));

                                                        scoreView.animateToEndState();
                                                    }
                                                });
                            }
                        },
                        2000);
            }
        } else if (type == EventBus.GAME_LOADED) {
            long loadTimeMs = (long) data;
            model.titleDurationMs = Math.max(0, model.titleDurationMs - loadTimeMs);
            SantaLog.d(TAG, "Waiting " + model.titleDurationMs + "ms and then hiding title.");
        }
    }

    private Drawable getShareImageDrawable(int starCount) {
        return ContextCompat.getDrawable(
                getActivity(), com.google.android.apps.santatracker.common.R.drawable.winner);
    }

    @Override
    protected void loadSounds() {
        super.loadSounds();
        soundManager.loadShortSound(context, R.raw.swimming_ice_splash_a);
        soundManager.loadShortSound(context, R.raw.present_throw_character_appear);
        soundManager.loadShortSound(context, R.raw.tennis_eliminate);
        soundManager.loadShortSound(context, R.raw.present_throw_block);
        soundManager.loadShortSound(context, R.raw.present_throw_goal);
        soundManager.loadShortSound(context, R.raw.present_throw_throw);
    }

    public boolean onTouchEvent(MotionEvent event) {
        if (gestureDetector != null) {
            gestureDetector.onTouchEvent(event);
            return true;
        }
        return false;
    }

    @Override
    protected void resume() {
        super.resume();
        if (uiRefreshHandler != null) {
            uiRefreshHandler.start(gameView);
        }
    }

    @Override
    protected String getGameType() {
        return PRESENT_TOSS_GAME_TYPE;
    }

    @Override
    protected float getScore() {
        return modelRef.get().score;
    }

    @Override
    protected int getShareImageId() {
        return getStarCount();
    }

    private int getStarCount() {
        final PresentTossModel model = modelRef.get();
        int starCount = 0;
        if (model.score > PresentTossModel.ONE_STAR_THRESHOLD) {
            starCount++;
        }
        if (model.score > PresentTossModel.TWO_STAR_THRESHOLD) {
            starCount++;
        }
        if (model.score > PresentTossModel.THREE_STAR_THRESHOLD) {
            starCount++;
        }
        return starCount;
    }

    @Override
    public boolean isGameOver() {
        return mIsGameOver;
    }

    /** The gesture detector for the water polo game. Supports the fling gesture. */
    private abstract class WaterPoloGestureDetector {
        float downX;
        float downY;

        void onTouchEvent(MotionEvent event) {
            switch (event.getAction()) {
                case (MotionEvent.ACTION_DOWN):
                    downX = event.getX();
                    downY = event.getY();

                    break;
                case (MotionEvent.ACTION_UP):
                case (MotionEvent.ACTION_OUTSIDE):
                    float velocityX = event.getX() - downX;
                    float velocityY = event.getY() - downY;

                    onPan((float) Math.atan2(velocityY, velocityX));
                    break;
            }
        }

        public abstract void onPan(float radians);
    }
}
