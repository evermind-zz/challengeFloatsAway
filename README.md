
## About
Display a floating window WebView to handle a cloudflare challenge.
It main goal was to start a Service from within an Okhttp Interceptor in case
we get a 403 there. It will try to fetch also the content of the page (html only) and
provide that data back into the interceptor

This library is not widly tested yet

#### Features:
- supports android sdk19
- floating window is controllable via EventBus
- get html data from WebView to be further processed
- get cookies
- overlay/floating permission handling
- 


#### Repository
Releases are distributed via jitpack. Make sure you include jitpack in
your `build.gradle` or `settings.gradle.kts`:

```gradle
dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		mavenCentral()
		maven { url = uri("https://jitpack.io") }
	}
}
```

```gradle
dependencies {
        implementation("com.github.evermind-zz:challengefloatsaway:Tag")
}
```

#### Usage
##### Configuration
We have to set up the library first. Eg set some domains we want to have the
cookies or what user agent WebView should use. Run interactive or not

create a method and call it somewhere if you want to override the default
```kotlin
    fun init(isInteractive: Boolean) {
        ChallengeSettings.update { current ->
            current.copy(
                userAgent = MY_USER_AGENT,
                cookieDomains = arrayOf<String>(
                    "https://example.com",
                    "example.com",
                    ".example.com"
                ),
                isInteractive = isInteractive
            )
        }
    }
```

##### Interceptor
Here it is used within an Interceptor implementation.
The `ChallengeServiceManager()` should be provided in the `Intercept403s`
It manages all the access to the Floating WebView/startup of the service etc.


```java
public class Intercept403s implements Interceptor {

    private final ChallengeManagerInterface bypassManager;

    @Override
    public Response intercept(final Chain chain) throws IOException {
        final Request request = chain.request();
    
        // eg: filter the urls you do not want to process
        if (!request.url().host().contains("example.com")) {
            return chain.proceed(request);
        }
    
        // eg: reuse previously retrieved cookies from the webView
        // not sure if it is any good as the cf signatures might differ
        final String cookies = bypassManager.getCurrentCookies();
        final Request.Builder builder = request.newBuilder();
        if (!cookies.isEmpty()) {
            builder.header("Cookie", cookies);
        }
    
        final Response response = chain.proceed(builder.build());
    
        if (response.code() == 200) {
            Log.d(TAG, "everything is working fine")
            return response;
        }
    
        if (response.code() == 403) {
            final ChallengeResult bypassResult =
                    bypassManager.fetchContentViaWebView(request.url().toString(), 30000);
    
    
            if (bypassResult.success && bypassResult.content != null) {
    
                // reuse the webView's content as a proper okHttp response.
                final Request newRequest = request.newBuilder().build();
                return new Response.Builder()
                        .request(newRequest)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create(HTML, bypassResult.content))
                        .build();
            }
        }
    }
```

