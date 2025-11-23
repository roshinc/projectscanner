package dev.roshin.projectscanner.analysis.model;

import dev.roshin.projectscanner.analysis.model.enums.TopicResolutionStatus;

import java.util.List;

/**
 * Represents a detected EDA (Event-Driven Architecture) publish call in the analyzed codebase.
 * Captures publishEvent() calls on IEventPublisher instances.
 *
 * @param topicName         The extracted or attempted topic name
 * @param topicStatus       Whether the topic name was successfully resolved
 * @param topicVariableType Type information if topic name couldn't be resolved (for debugging)
 * @param usageLocation     Where in the code this publish call occurs
 * @param callChain         The complete call chain from usage to the highest public method (entry point)
 * @param messageDataType   Type of MessageData if detectable, null otherwise
 */
public record EdaPublishUsage(
        String topicName,
        TopicResolutionStatus topicStatus,
        String topicVariableType,
        SourceLocation usageLocation,
        List<CallChainEntry> callChain,
        String messageDataType
) {
    @Override
    public String toString() {
        return String.format("EDA[topic=%s, status=%s] at %s",
                topicName, topicStatus, usageLocation);
    }
}
