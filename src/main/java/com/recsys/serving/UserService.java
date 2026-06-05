package com.recsys.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.infrastructure.DataManager;
import com.recsys.model.User;

import java.util.concurrent.CompletableFuture;

public class UserService extends BaseApiService {

    private final DataManager dataManager;

    public UserService(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
            try {
                int userId = requiredIntParam(ctx, "userId");
                User user = dataManager.getUserById(userId);
                if (user == null) return writeError(HttpStatus.NOT_FOUND, "user not found", "userId", userId);
                return writeJson(HttpStatus.OK, user);
            } catch (BadRequestException e) {
                return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error in UserService", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }
}
