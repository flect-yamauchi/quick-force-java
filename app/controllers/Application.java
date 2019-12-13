package controllers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.typesafe.config.Config;
import play.libs.Json;
import play.libs.ws.WSClient;
import play.libs.ws.WSResponse;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import views.html.index;
import views.html.setup;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class Application extends Controller {
    @Inject
    private Force force;

    private boolean isSetup() {
        try {
            force.consumerKey();
            force.consumerSecret();
            force.consumerBasedomain();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String oauthCallbackUrl(Http.Request request) {
        return (request.secure() ? "https" : "http") + "://" + request.host();
    }

    public CompletionStage<Result> index(String code) {
        if (isSetup()) {
            if (code == null) {
                // start oauth
                final String url = "https://"+force.consumerBasedomain()+"/services/oauth2/authorize?response_type=code" +
                        "&client_id=" + force.consumerKey() +
                        "&redirect_uri=" + oauthCallbackUrl(request());
                return CompletableFuture.completedFuture(redirect(url));
            } else {
                return force.getToken(code, oauthCallbackUrl(request())).thenCompose(authInfo ->
                        force.getAccounts(authInfo).thenApply(accounts ->
                                ok(index.render(accounts))
                        )
                ).exceptionally(error -> {
                    if (error.getCause() instanceof Force.AuthException)
                        return redirect(routes.Application.index(null));
                    else
                        return internalServerError(error.getMessage());
                });
            }
        } else {
            return CompletableFuture.completedFuture(redirect(routes.Application.setup()));
        }
    }

    public Result setup() {
        if (isSetup()) {
            return redirect(routes.Application.index(null));
        } else {
            final String maybeHerokuAppName = request().host().split(".herokuapp.com")[0].replaceAll(request().host(), "");
            return ok(setup.render(maybeHerokuAppName));
        }
    }


    @Singleton
    public static class Force {
        @Inject
        WSClient ws;

        @Inject
        Config config;

        String consumerKey() {
            return config.getString("consumer.key");
        }

        String consumerSecret() {
            return config.getString("consumer.secret");
        }
        
        String consumerBasedomain() {
            return config.getString("consumer.basedomain");
        }

        CompletionStage<AuthInfo> getToken(String code, String redirectUrl) {
            final CompletionStage<WSResponse> responsePromise = ws.url("https://"+consumerBasedomain()+"/services/oauth2/token")
                    .addQueryParameter("grant_type", "authorization_code")
                    .addQueryParameter("code", code)
                    .addQueryParameter("client_id", consumerKey())
                    .addQueryParameter("client_secret", consumerSecret())
                    .addQueryParameter("redirect_uri", redirectUrl)
                    .execute(Http.HttpVerbs.POST);

            return responsePromise.thenCompose(response -> {
                final JsonNode jsonNode = response.asJson();

                if (jsonNode.has("error")) {
                    CompletableFuture<AuthInfo> completableFuture = new CompletableFuture<>();
                    completableFuture.completeExceptionally(new AuthException(jsonNode.get("error").textValue()));
                    return completableFuture;
                } else {
                    return CompletableFuture.completedFuture(Json.fromJson(jsonNode, AuthInfo.class));
                }
            });
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Account {
            public String Id;
            public String Name;
            public String Type;
            public String Industry;
            public String Rating;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class QueryResultAccount {
            public List<Account> records;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class AuthInfo {
            @JsonProperty("access_token")
            public String accessToken;

            @JsonProperty("instance_url")
            public String instanceUrl;
        }

        public static class AuthException extends Exception {
            AuthException(String message) {
                super(message);
            }
        }

        CompletionStage<List<Account>> getAccounts(AuthInfo authInfo) {
            System.out.println("authInfo.accessToken:"+authInfo.accessToken);
            System.out.println("authInfo.instanceUrl:"+authInfo.instanceUrl);
            CompletionStage<WSResponse> responsePromise = ws.url(authInfo.instanceUrl + "/services/data/v34.0/query/")
                    .addHeader("Authorization", "Bearer " + authInfo.accessToken)
                    .addQueryParameter("q", "SELECT Id, Name FROM ccrz__E_Product__c")
                    .get();

            return responsePromise.thenCompose(response -> {
                final JsonNode jsonNode = response.asJson();
                if (jsonNode.has("error")) {
                    CompletableFuture<List<Account>> completableFuture = new CompletableFuture<>();
                    completableFuture.completeExceptionally(new AuthException(jsonNode.get("error").textValue()));
                    return completableFuture;
                } else {
                    System.out.println("SUCCESS:QUERY RUNNIG");
                    System.out.println("JSON value:"+jsonNode.textValue());
                    QueryResultAccount queryResultAccount = Json.fromJson(jsonNode, QueryResultAccount.class);
                    return CompletableFuture.completedFuture(queryResultAccount.records);
                }
            });
        }
    }

}
