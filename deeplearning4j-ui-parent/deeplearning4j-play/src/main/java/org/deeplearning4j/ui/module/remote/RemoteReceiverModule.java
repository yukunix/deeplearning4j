package org.deeplearning4j.ui.module.remote;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.api.storage.*;
import org.deeplearning4j.ui.api.FunctionType;
import org.deeplearning4j.ui.api.HttpMethod;
import org.deeplearning4j.ui.api.Route;
import org.deeplearning4j.ui.api.UIModule;
import org.nd4j.shade.jackson.core.type.TypeReference;
import org.nd4j.shade.jackson.databind.ObjectMapper;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;

import javax.xml.bind.DatatypeConverter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static play.mvc.Http.Context.Implicit.request;

/**
 *
 * Used to receive UI updates remotely.
 * Used in conjunction with {@link org.deeplearning4j.api.storage.impl.RemoteUIStatsStorageRouter}, which posts to the UI.
 * UI information is then deserialized and routed to the specified StatsStorageRouter, which may (or may not)
 * be attached to the UI
 *
 * @author Alex Black
 */
@Slf4j
public class RemoteReceiverModule implements UIModule {

    private AtomicBoolean enabled = new AtomicBoolean(false);
    private StatsStorageRouter statsStorage;

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        if (!enabled) {
            this.statsStorage = null;
        }
    }

    public boolean isEnabled() {
        return enabled.get() && this.statsStorage != null;
    }

    public void setStatsStorage(StatsStorageRouter statsStorage) {
        this.statsStorage = statsStorage;
    }

    @Override
    public List<String> getCallbackTypeIDs() {
        return Collections.emptyList();
    }

    @Override
    public List<Route> getRoutes() {
        Route r = new Route("/remoteReceive", HttpMethod.POST, FunctionType.Supplier, this::receiveData);
        return Collections.singletonList(r);
    }

    @Override
    public void reportStorageEvents(Collection<StatsStorageEvent> events) {
        //No op

    }

    @Override
    public void onAttach(StatsStorage statsStorage) {
        //No op
    }

    @Override
    public void onDetach(StatsStorage statsStorage) {
        //No op
    }

    private Result receiveData() {
        if (!enabled.get()) {
            return Results.forbidden(
                            "UI server remote listening is currently disabled. Use UIServer.getInstance().enableRemoteListener()");
        }

        if (statsStorage == null) {
            return Results.internalServerError(
                            "UI Server remote listener: no StatsStorage instance is set/available to store results");
        }

        JsonNode jn = request().body().asJson();
        JsonNode type = jn.get("type");
        JsonNode dataClass = jn.get("class");
        JsonNode data = jn.get("data");

        if (type == null || dataClass == null || data == null) {

            log.warn("Received incorrectly formatted data from remote listener (has type = " + (type != null)
                            + ", has data class = " + (dataClass != null) + ", has data = " + (data != null) + ")");
            return Results.badRequest("Received incorrectly formatted data");
        }

        String dc = dataClass.asText();
        String content = data.asText();

        switch (type.asText().toLowerCase()) {
            case "metadata":
                StorageMetaData meta = getMetaData(dc, content);
                if (meta != null) {
                    statsStorage.putStorageMetaData(meta);
                }
                break;
            case "staticinfo":
                Persistable staticInfo = getPersistable(dc, content);
                if (staticInfo != null) {
                    statsStorage.putStaticInfo(staticInfo);
                }
                break;
            case "update":
                Persistable update = getPersistable(dc, content);
                if (update != null) {
                    statsStorage.putUpdate(update);
                }
                break;
            default:

        }

        return Results.ok("Receiver got data: ");
    }

    private StorageMetaData getMetaData(String dataClass, String content) {

        StorageMetaData meta;
        try {
            Class<?> c = Class.forName(dataClass);
            if (StorageMetaData.class.isAssignableFrom(c)) {
                meta = (StorageMetaData) c.newInstance();
            } else {
                log.warn("Skipping invalid remote data: class {} in not an instance of {}", dataClass,
                                StorageMetaData.class.getName());
                return null;
            }
        } catch (Exception e) {
            log.warn("Skipping invalid remote data: exception encountered for class {}", dataClass, e);
            return null;
        }

        try {
            byte[] bytes = DatatypeConverter.parseBase64Binary(content);
            meta.decode(bytes);
        } catch (Exception e) {
            log.warn("Skipping invalid remote UI data: exception encountered when deserializing data", e);
            return null;
        }

        return meta;
    }

    private Persistable getPersistable(String dataClass, String content) {
        Persistable p;
        try {
            Class<?> c = Class.forName(dataClass);
            if (Persistable.class.isAssignableFrom(c)) {
                p = (Persistable) c.newInstance();
            } else {
                log.warn("Skipping invalid remote data: class {} in not an instance of {}", dataClass,
                                Persistable.class.getName());
                return null;
            }
        } catch (Exception e) {
            log.warn("Skipping invalid remote UI data: exception encountered for class {}", dataClass, e);
            return null;
        }

        try {
            byte[] bytes = DatatypeConverter.parseBase64Binary(content);
            p.decode(bytes);
        } catch (Exception e) {
            log.warn("Skipping invalid remote data: exception encountered when deserializing data", e);
            return null;
        }

        return p;
    }
}
