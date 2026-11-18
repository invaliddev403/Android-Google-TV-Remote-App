package com.invaliddev403.gtvremote;

import android.app.Application;

public final class GtvRemoteApplication extends Application implements TvRemoteController.Listener {
    private TvRemoteController remoteController;
    private TvRemoteController.Listener listener;

    @Override
    public void onCreate() {
        super.onCreate();
        remoteController = new TvRemoteController(this);
    }

    TvRemoteController remoteController() {
        return remoteController;
    }

    void setRemoteListener(TvRemoteController.Listener listener) {
        this.listener = listener;
    }

    void clearRemoteListener(TvRemoteController.Listener listener) {
        if (this.listener == listener) this.listener = null;
    }

    @Override
    public void onStateChanged(TvRemoteController.State state, String host, String message) {
        if (listener != null) listener.onStateChanged(state, host, message);
    }

    @Override
    public void onDeviceInfoChanged() {
        if (listener != null) listener.onDeviceInfoChanged();
    }

    @Override
    public void onError(String message) {
        if (listener != null) listener.onError(message);
    }
}
