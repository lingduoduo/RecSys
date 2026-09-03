package com.recsys.application.retrieval;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.TensorInfo;
import com.recsys.application.feature.FeatureEncoder;
import com.recsys.application.model.ModelArtifactManifest.ModelContract;
import com.recsys.config.ModelServingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract validation against a mocked session adapter, so every malformed-model shape is
 * covered without generating malformed ONNX binaries. The real-model lifecycle lives in
 * {@link UserTowerInferenceServiceTest}.
 */
class UserTowerInferenceContractTest {

    private static final byte[] MODEL = "model".getBytes();
    private final OnnxSessionHandle handle = mock(OnnxSessionHandle.class);
    private final OnnxSessionFactory factory = mock(OnnxSessionFactory.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private UserTowerInferenceService service(ModelContract contract) throws OrtException {
        when(factory.open(any(), any())).thenReturn(handle);
        return new UserTowerInferenceService(MODEL, contract,
                new ModelServingProperties.Onnx(), "training", registry, factory);
    }

    private void stubValidMetadata() throws OrtException {
        when(handle.inputInfo()).thenReturn(inputs("user_id", int64Rank1(), "item_id", int64Rank1()));
        when(handle.outputInfo()).thenReturn(Map.of("score", node("score", floatRank1())));
    }

    @Test
    void initAppliesConfiguredOptionsValidatesMetadataAndSmokes() throws Exception {
        stubValidMetadata();
        when(handle.run(eq("user_id"), eq("item_id"), eq("score"), any(), any())).thenReturn(new float[]{0.25f});
        ModelServingProperties.Onnx onnx = new ModelServingProperties.Onnx();
        onnx.setIntraOpThreads(3);
        when(factory.open(any(), any())).thenReturn(handle);
        UserTowerInferenceService service = new UserTowerInferenceService(
                MODEL, ModelContract.legacy(), onnx, "training", registry, factory);

        service.init();

        verify(factory).open(eq(MODEL), same(onnx));
        verify(handle).run(eq("user_id"), eq("item_id"), eq("score"),
                eq(new long[]{0L}), eq(new long[]{0L}));
        assertThat(service.isReady()).isTrue();
        assertThat(service.runCount()).isEqualTo(1);
        assertThat(registry.get("recsys.model.onnx.runs").tag("variant", "training").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void missingRequiredInputRejectsAndClosesSession() throws Exception {
        when(handle.inputInfo()).thenReturn(inputs("user_id", int64Rank1()));
        when(handle.outputInfo()).thenReturn(Map.of("score", node("score", floatRank1())));
        UserTowerInferenceService service = service(ModelContract.legacy());

        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("item_id");
        verify(handle).close();
        verify(handle, never()).run(anyString(), anyString(), anyString(), any(), any());
        assertThat(service.isReady()).isFalse();
    }

    @Test
    void unexpectedAdditionalInputIsRejected() throws Exception {
        when(handle.inputInfo()).thenReturn(inputs(
                "user_id", int64Rank1(), "item_id", int64Rank1(), "context", int64Rank1()));
        when(handle.outputInfo()).thenReturn(Map.of("score", node("score", floatRank1())));
        UserTowerInferenceService service = service(ModelContract.legacy());

        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("context");
        verify(handle).close();
    }

    @Test
    void wrongInputTypeIsRejected() throws Exception {
        when(handle.inputInfo()).thenReturn(inputs("user_id", int64Rank1(), "item_id", int32Rank1()));
        when(handle.outputInfo()).thenReturn(Map.of("score", node("score", floatRank1())));
        UserTowerInferenceService service = service(ModelContract.legacy());

        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("item_id")
                .hasMessageContaining("INT64");
    }

    @Test
    void wrongInputRankIsRejected() throws Exception {
        when(handle.inputInfo()).thenReturn(inputs("user_id", int64Rank2(), "item_id", int64Rank1()));
        when(handle.outputInfo()).thenReturn(Map.of("score", node("score", floatRank1())));
        UserTowerInferenceService service = service(ModelContract.legacy());

        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user_id")
                .hasMessageContaining("rank");
    }

    @Test
    void missingOutputIsRejected() throws Exception {
        when(handle.inputInfo()).thenReturn(inputs("user_id", int64Rank1(), "item_id", int64Rank1()));
        when(handle.outputInfo()).thenReturn(Map.of("logits", node("logits", floatRank1())));
        UserTowerInferenceService service = service(ModelContract.legacy());

        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("score");
    }

    @Test
    void wrongOutputTypeIsRejectedButExtraOutputsAreIgnored() throws Exception {
        when(handle.inputInfo()).thenReturn(inputs("user_id", int64Rank1(), "item_id", int64Rank1()));
        when(handle.outputInfo()).thenReturn(Map.of(
                "score", node("score", int64Rank1()),
                "debug", node("debug", floatRank1())));
        UserTowerInferenceService service = service(ModelContract.legacy());

        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("score")
                .hasMessageContaining("FLOAT");
    }

    @Test
    void extraOutputsAloneDoNotFailInit() throws Exception {
        when(handle.inputInfo()).thenReturn(inputs("user_id", int64Rank1(), "item_id", int64Rank1()));
        when(handle.outputInfo()).thenReturn(Map.of(
                "score", node("score", floatRank1()),
                "debug", node("debug", floatRank1())));
        when(handle.run(anyString(), anyString(), anyString(), any(), any())).thenReturn(new float[]{1f});
        UserTowerInferenceService service = service(ModelContract.legacy());

        service.init();

        assertThat(service.isReady()).isTrue();
    }

    @Test
    void manifestContractOutputNameIsAuthoritative() throws Exception {
        ModelContract contract = new ModelContract("user_id", "item_id",
                com.recsys.application.model.ModelArtifactManifest.TensorType.INT64, 1,
                "prob", com.recsys.application.model.ModelArtifactManifest.TensorType.FLOAT, 1);
        when(handle.inputInfo()).thenReturn(inputs("user_id", int64Rank1(), "item_id", int64Rank1()));
        when(handle.outputInfo()).thenReturn(Map.of("prob", node("prob", floatRank1())));
        when(handle.run(eq("user_id"), eq("item_id"), eq("prob"), any(), any())).thenReturn(new float[]{0.5f});
        UserTowerInferenceService service = service(contract);

        service.init();

        assertThat(service.score(new FeatureEncoder.EncodedFeatures(1L), 7L)).isEqualTo(0.5);
    }

    @Test
    void smokeInferenceWithWrongLengthFailsInit() throws Exception {
        stubValidMetadata();
        when(handle.run(anyString(), anyString(), anyString(), any(), any())).thenReturn(new float[]{1f, 2f});
        UserTowerInferenceService service = service(ModelContract.legacy());

        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("smoke");
        verify(handle).close();
        assertThat(service.isReady()).isFalse();
    }

    @Test
    void smokeInferenceWithNonFiniteScoreFailsInit() throws Exception {
        stubValidMetadata();
        when(handle.run(anyString(), anyString(), anyString(), any(), any())).thenReturn(new float[]{Float.NaN});
        UserTowerInferenceService service = service(ModelContract.legacy());

        assertThatThrownBy(service::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finite");
        verify(handle).close();
    }

    @Test
    void sessionOpenFailureLeavesServiceNotReady() throws Exception {
        when(factory.open(any(), any())).thenThrow(new OrtException("bad model"));
        UserTowerInferenceService service = new UserTowerInferenceService(
                MODEL, ModelContract.legacy(), new ModelServingProperties.Onnx(), "training", registry, factory);

        assertThatThrownBy(service::init).isInstanceOf(OrtException.class);
        assertThat(service.isReady()).isFalse();
    }

    @Test
    void shortBatchOutputIsAContractViolationAtRuntime() throws Exception {
        stubValidMetadata();
        when(handle.run(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new float[]{1f})            // smoke
                .thenReturn(new float[]{1f});           // batch of 2 answered with 1
        UserTowerInferenceService service = service(ModelContract.legacy());
        service.init();

        assertThatThrownBy(() -> service.scoreCandidates(
                new FeatureEncoder.EncodedFeatures(1L), encoder(), java.util.Set.of("1", "2"), 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2")
                .hasMessageContaining("1");
        assertThat(service.runCount()).isEqualTo(2);
    }

    @Test
    void nonFiniteRuntimeScoreIsAContractViolation() throws Exception {
        stubValidMetadata();
        when(handle.run(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new float[]{1f})
                .thenReturn(new float[]{Float.POSITIVE_INFINITY});
        UserTowerInferenceService service = service(ModelContract.legacy());
        service.init();

        assertThatThrownBy(() -> service.score(new FeatureEncoder.EncodedFeatures(1L), 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finite");
    }

    @Test
    void closeIsIdempotentAndClearsReadiness() throws Exception {
        stubValidMetadata();
        when(handle.run(anyString(), anyString(), anyString(), any(), any())).thenReturn(new float[]{1f});
        UserTowerInferenceService service = service(ModelContract.legacy());
        service.init();

        service.close();
        service.close();

        verify(handle).close();
        assertThat(service.isReady()).isFalse();
        assertThatThrownBy(() -> service.score(new FeatureEncoder.EncodedFeatures(1L), 1L))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- fixtures ---

    private static FeatureEncoder encoder() {
        return new FeatureEncoder(new com.recsys.application.model.ModelArtifactService(
                new com.recsys.application.model.ModelArtifactLocator("", ""), null) {
            @Override public Map<String, Integer> getUserVocab() { return Map.of("__UNK__", 0, "1", 1); }
            @Override public Map<String, Integer> getItemVocab() { return Map.of("1", 1, "2", 2); }
        });
    }

    private static Map<String, NodeInfo> inputs(Object... namesAndInfos) {
        Map<String, NodeInfo> map = new LinkedHashMap<>();
        for (int i = 0; i < namesAndInfos.length; i += 2) {
            String name = (String) namesAndInfos[i];
            map.put(name, node(name, (TensorInfo) namesAndInfos[i + 1]));
        }
        return map;
    }

    private static NodeInfo node(String name, TensorInfo info) {
        return new NodeInfo(name, info);
    }

    private static TensorInfo int64Rank1() throws OrtException {
        return TensorInfo.constructFromJavaArray(new long[]{1L});
    }

    private static TensorInfo int64Rank2() throws OrtException {
        return TensorInfo.constructFromJavaArray(new long[][]{{1L}});
    }

    private static TensorInfo int32Rank1() throws OrtException {
        return TensorInfo.constructFromJavaArray(new int[]{1});
    }

    private static TensorInfo floatRank1() throws OrtException {
        return TensorInfo.constructFromJavaArray(new float[]{1f});
    }
}
