package com.github.evermindzz.challengefloatsaway.manager;

import com.github.evermindzz.challengefloatsaway.ChallengeResult;

public interface ChallengeManagerInterface {

    ChallengeResult fetchContentViaWebView(String url, long timeoutMs);

    String getCurrentCookies();

    void destroy();

}