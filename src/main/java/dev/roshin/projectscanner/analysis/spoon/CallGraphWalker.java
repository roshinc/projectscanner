package dev.roshin.projectscanner.analysis.spoon;

import dev.roshin.projectscanner.analysis.config.AnalysisConfig;
import dev.roshin.projectscanner.analysis.model.CallChainEntry;
import dev.roshin.projectscanner.analysis.model.enums.Visibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds call chains by traversing upward from usage locations to entry points.
 * An entry point is the highest reachable public method with no callers in the scanned codebase.
 */
public class CallGraphWalker {
    private static final Logger log = LoggerFactory.getLogger(CallGraphWalker.class);

    private final CtModel model;
    private final AnalysisConfig config;
    private final List<String> warnings;

    // Cache for method callers to avoid repeated scans
    private final Map<String, List<CtMethod<?>>> methodCallersCache = new HashMap<>();

    public CallGraphWalker(CtModel model, AnalysisConfig config, List<String> warnings) {
        this.model = model;
        this.config = config;
        this.warnings = warnings;
    }

    /**
     * Builds the call chain from a usage element to its entry point(s).
     *
     * @param usageElement The element where the usage occurs (invocation, constructor call, etc.)
     * @return List of call chains (one per entry point if multiple exist)
     */
    public List<List<CallChainEntry>> buildCallChains(spoon.reflect.declaration.CtElement usageElement) {
        log.debug("Building call chain from usage at {}", getLocationString(usageElement));

        // Find the method containing this usage
        CtMethod<?> containingMethod = usageElement.getParent(CtMethod.class);
        if (containingMethod == null) {
            log.debug("Usage not within a method, checking for initializer or field");
            // Could be in a field initializer or static block
            return List.of(List.of()); // Empty call chain
        }

        // Build the call chain starting from this method
        Set<String> visited = new HashSet<>();
        List<CallChainEntry> chain = new ArrayList<>();

        try {
            List<List<CallChainEntry>> chains = traceUpward(containingMethod, visited, chain, 0);

            if (chains.isEmpty()) {
                // No entry point found - the containing method itself might be unreachable
                log.debug("No entry point found, using containing method as single entry");
                CallChainEntry entry = createCallChainEntry(containingMethod, true);
                return List.of(List.of(entry));
            }

            return chains;
        } catch (Exception e) {
            log.warn("Error building call chain: {}", e.getMessage());
            warnings.add("Call chain construction failed: " + e.getMessage());
            return List.of(List.of());
        }
    }

    /**
     * Recursively traces upward through the call graph.
     *
     * @param method       Current method being analyzed
     * @param visited      Set of visited method signatures to detect cycles
     * @param currentChain Current chain being built
     * @param depth        Current depth in the traversal
     * @return List of complete call chains (one per entry point)
     */
    private List<List<CallChainEntry>> traceUpward(
            CtMethod<?> method,
            Set<String> visited,
            List<CallChainEntry> currentChain,
            int depth) {

        String methodSignature = getMethodSignature(method);

        // Check for circular reference
        if (visited.contains(methodSignature)) {
            log.warn("Circular reference detected: {}", methodSignature);
            warnings.add("Circular reference detected in " + methodSignature);
            return List.of(); // Stop this branch
        }

        // Check depth limit
        if (!config.isUnlimited() && depth >= config.maxDepth()) {
            log.debug("Max depth {} reached at {}", config.maxDepth(), methodSignature);
            warnings.add("Max depth reached at " + methodSignature);
            // Mark current method as entry point due to depth limit
            CallChainEntry entry = createCallChainEntry(method, true);
            List<CallChainEntry> finalChain = new ArrayList<>(currentChain);
            finalChain.add(entry);
            return List.of(finalChain);
        }

        // Add to visited set
        visited.add(methodSignature);

        // Add current method to chain
        CallChainEntry currentEntry = createCallChainEntry(method, false);
        currentChain.add(currentEntry);

        // Check if this is a public method
        boolean isPublic = method.isPublic();

        // Find all callers of this method
        List<CtMethod<?>> callers = findCallers(method);

        if (callers.isEmpty()) {
            // No callers found
            if (isPublic) {
                // This is an entry point (public method with no callers)
                log.debug("Found entry point: {}", methodSignature);
                currentEntry = createCallChainEntry(method, true); // Mark as entry point
                List<CallChainEntry> finalChain = new ArrayList<>(currentChain);
                finalChain.set(finalChain.size() - 1, currentEntry); // Replace with entry point version
                visited.remove(methodSignature);
                return List.of(finalChain);
            } else {
                // Private/protected/package method with no callers - dead code or entry for non-public API
                log.debug("Found private/protected method with no callers: {}", methodSignature);
                currentEntry = createCallChainEntry(method, true); // Mark as entry point anyway
                List<CallChainEntry> finalChain = new ArrayList<>(currentChain);
                finalChain.set(finalChain.size() - 1, currentEntry);
                visited.remove(methodSignature);
                return List.of(finalChain);
            }
        }

        // Continue tracing upward through all callers
        List<List<CallChainEntry>> allChains = new ArrayList<>();

        for (CtMethod<?> caller : callers) {
            // Create a new branch for each caller
            Set<String> branchVisited = new HashSet<>(visited);
            List<CallChainEntry> branchChain = new ArrayList<>(currentChain);

            List<List<CallChainEntry>> callerChains = traceUpward(caller, branchVisited, branchChain, depth + 1);
            allChains.addAll(callerChains);
        }

        // Remove from visited for this branch
        visited.remove(methodSignature);

        return allChains;
    }

    /**
     * Finds all methods that call the given method.
     */
    private List<CtMethod<?>> findCallers(CtMethod<?> targetMethod) {
        String methodSignature = getMethodSignature(targetMethod);

        // Check cache first
        if (methodCallersCache.containsKey(methodSignature)) {
            return methodCallersCache.get(methodSignature);
        }

        log.debug("Finding callers of: {}", methodSignature);

        List<CtMethod<?>> callers = new ArrayList<>();
        String targetMethodName = targetMethod.getSimpleName();
        CtType<?> targetClass = targetMethod.getDeclaringType();

        if (targetClass == null) {
            return callers;
        }

        String targetClassName = targetClass.getQualifiedName();

        // Get all invocations in the model
        List<CtInvocation<?>> allInvocations = model.getElements(new TypeFilter<>(CtInvocation.class));

        for (CtInvocation<?> invocation : allInvocations) {
            try {
                // Check if this invocation calls our target method
                if (isInvocationOfMethod(invocation, targetMethod, targetMethodName, targetClassName)) {
                    // Find the method containing this invocation
                    CtMethod<?> callingMethod = invocation.getParent(CtMethod.class);
                    if (callingMethod != null && !callingMethod.equals(targetMethod)) {
                        callers.add(callingMethod);
                    }
                }
            } catch (Exception e) {
                log.trace("Error checking invocation: {}", e.getMessage());
            }
        }

        // Remove duplicates
        callers = callers.stream().distinct().collect(Collectors.toList());

        log.debug("Found {} callers for {}", callers.size(), methodSignature);

        // Cache the result
        methodCallersCache.put(methodSignature, callers);

        return callers;
    }

    /**
     * Checks if an invocation calls the target method.
     */
    private boolean isInvocationOfMethod(
            CtInvocation<?> invocation,
            CtMethod<?> targetMethod,
            String targetMethodName,
            String targetClassName) {

        CtExecutableReference<?> executable = invocation.getExecutable();
        if (executable == null) {
            return false;
        }

        // Check method name
        if (!targetMethodName.equals(executable.getSimpleName())) {
            return false;
        }

        // Check declaring type
        if (executable.getDeclaringType() == null) {
            return false;
        }

        String invocationClassName = executable.getDeclaringType().getQualifiedName();
        if (invocationClassName == null || !invocationClassName.equals(targetClassName)) {
            return false;
        }

        // Match parameter count
        if (executable.getParameters() == null ||
                executable.getParameters().size() != targetMethod.getParameters().size()) {
            return false;
        }

        // Match parameter types
//        for (int i = 0; i < executable.getParameters().size(); i++) {
//            String callType = executable.getParameters().get(i).getQualifiedName();
//            String targetType = targetMethod.getParameters().get(i).getType().getQualifiedName();
//            if (!callType.equals(targetType)) return false;
//        }

        // Optionally check parameter types for overloaded methods
        // For now, matching by name and class is sufficient

        return true;
    }

    /**
     * Creates a CallChainEntry from a method.
     */
    private CallChainEntry createCallChainEntry(CtMethod<?> method, boolean isEntryPoint) {
        String className = "unknown";
        String methodSignature = "unknown";
        Visibility visibility = Visibility.PACKAGE;
        int lineNumber = -1;

        try {
            // Get class name
            CtType<?> declaringType = method.getDeclaringType();
            if (declaringType != null) {
                className = declaringType.getQualifiedName();
            }

            // Get method signature
            methodSignature = buildMethodSignature(method);

            // Get visibility
            visibility = determineVisibility(method);

            // Get line number
            if (method.getPosition() != null && method.getPosition().isValidPosition()) {
                lineNumber = method.getPosition().getLine();
            }
        } catch (Exception e) {
            log.debug("Error creating call chain entry: {}", e.getMessage());
        }

        return new CallChainEntry(className, methodSignature, visibility, lineNumber, isEntryPoint);
    }

    /**
     * Determines the visibility of a method.
     */
    private Visibility determineVisibility(CtMethod<?> method) {
        if (method.isPublic()) {
            return Visibility.PUBLIC;
        } else if (method.isPrivate()) {
            return Visibility.PRIVATE;
        } else if (method.isProtected()) {
            return Visibility.PROTECTED;
        } else {
            return Visibility.PACKAGE;
        }
    }

    /**
     * Builds a method signature including parameter types.
     */
    private String buildMethodSignature(CtMethod<?> method) {
        StringBuilder signature = new StringBuilder();
        signature.append(method.getSimpleName());
        signature.append("(");

        List<CtParameter<?>> parameters = method.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            CtParameter<?> param = parameters.get(i);
            if (param.getType() != null) {
                signature.append(param.getType().getQualifiedName());
            } else {
                signature.append("?");
            }
            if (i < parameters.size() - 1) {
                signature.append(", ");
            }
        }

        signature.append(")");
        return signature.toString();
    }

    /**
     * Gets a unique signature for a method (for cycle detection).
     */
    private String getMethodSignature(CtMethod<?> method) {
        String className = method.getDeclaringType() != null ?
                method.getDeclaringType().getQualifiedName() : "unknown";
        return className + "." + buildMethodSignature(method);
    }

    /**
     * Gets a location string for logging.
     */
    private String getLocationString(spoon.reflect.declaration.CtElement element) {
        try {
            if (element.getPosition() != null && element.getPosition().isValidPosition()) {
                return element.getPosition().toString();
            }
        } catch (Exception e) {
            // Ignore
        }
        return "unknown location";
    }

    /**
     * Clears the method callers cache. Useful for memory management in large projects.
     */
    public void clearCache() {
        methodCallersCache.clear();
    }

    /**
     * Returns statistics about the cache.
     */
    public Map<String, Integer> getCacheStats() {
        return Map.of(
                "cachedMethods", methodCallersCache.size(),
                "totalCallers", methodCallersCache.values().stream()
                        .mapToInt(List::size)
                        .sum()
        );
    }
}
