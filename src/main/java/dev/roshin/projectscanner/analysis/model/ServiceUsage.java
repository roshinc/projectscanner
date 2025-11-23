package dev.roshin.projectscanner.analysis.model;

import dev.roshin.projectscanner.analysis.model.enums.UsageType;

import java.util.List;

/**
 * Represents a detected usage of a service dependency in the analyzed codebase.
 * Captures instantiations, method calls, and static method usage.
 *
 * @param serviceId      The service identifier (e.g., "MDZ017J")
 * @param servicePackage The package of the service (e.g., "dev.myorg.services.mdz017j")
 * @param usageType      Type of usage: instantiation, method call, constructor, or static method
 * @param usageLocation  Where in the code this usage occurs
 * @param callChain      The complete call chain from usage to the highest public method (entry point)
 * @param targetClass    Specific class from the service being used
 * @param targetMethod   Method being invoked (if applicable), null for instantiations
 */
public record ServiceUsage(
        String serviceId,
        String servicePackage,
        UsageType usageType,
        SourceLocation usageLocation,
        List<CallChainEntry> callChain,
        String targetClass,
        String targetMethod
) {
    @Override
    public String toString() {
        String method = targetMethod != null ? "." + targetMethod + "()" : "";
        return String.format("Service[%s] %s: %s%s at %s",
                serviceId, usageType, targetClass, method, usageLocation);
    }
}
