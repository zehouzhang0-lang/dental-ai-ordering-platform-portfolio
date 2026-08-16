package com.yuri.aiorder.workflow.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ProcessEdgeResponse(
        @JsonProperty("edge_instance_id") long edgeInstanceId,
        @JsonProperty("from_node_instance_id") long fromNodeInstanceId,
        @JsonProperty("to_node_instance_id") long toNodeInstanceId,
        @JsonProperty("edge_type") String edgeType) {
}
