package dev.roshin.projectscanner.analysis.spoon;

import dev.roshin.projectscanner.analysis.config.AnalysisConfig;
import dev.roshin.projectscanner.analysis.model.*;
import dev.roshin.projectscanner.analysis.model.enums.TopicResolutionStatus;
import dev.roshin.projectscanner.analysis.model.enums.UsageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.*;

/**
 * Detects usage patterns in the Spoon AST model.
 * Identifies function client invocations, service usages, and EDA publish calls.
 */
public class UsageDetector {
    private static final Logger log = LoggerFactory.getLogger(UsageDetector.class);

    // Package patterns
    private static final String FUNCTION_PACKAGE_PATTERN = "dev.myorg.mysection.function.client";
    private static final String SERVICE_PACKAGE_PATTERN = "dev.myorg.services";
    private static final String EDA_PUBLISHER_TYPE = "dev.myorg.mysection.eda.publisher.service.IEventPublisher";
    private static final String MESSAGE_DATA_TYPE = "dev.myorg.mysection.eda.publisher.domain.MessageData";

    // Function client method names
    private static final Set<String> FUNCTION_METHODS = Set.of(
            "execute", "executeAsync", "executeAsyncOnOrAfter"
    );

    private final CtModel model;
    private final AnalysisConfig config;
    private final CallGraphWalker callGraphWalker;

    public UsageDetector(CtModel model, AnalysisConfig config, CallGraphWalker callGraphWalker) {
        this.model = model;
        this.config = config;
        this.callGraphWalker = callGraphWalker;
    }

    /**
     * Detects all function client invocations in the model.
     * <p>
     * Pattern: <FunctionId>Function.instance().<method>(...)
     * Package: dev.myorg.mysection.function.client.<FunctionId>Function
     * Methods: execute, executeAsync, executeAsyncOnOrAfter
     */
    public List<FunctionClientUsage> detectFunctionClientUsages(Set<String> functionIds) {
        log.info("Detecting function client usages for {} functions", functionIds.size());
        List<FunctionClientUsage> usages = new ArrayList<>();

        if (functionIds.isEmpty()) {
            log.debug("No function IDs to detect");
            return usages;
        }

        // Build expected class names: ProcessWtPayments -> ProcessWtPaymentsFunction
        Set<String> functionClassNames = new HashSet<>();
        for (String functionId : functionIds) {
            functionClassNames.add(functionId + "Function");
        }

        // Find all method invocations
        List<CtInvocation<?>> invocations = model.getElements(new TypeFilter<>(CtInvocation.class));
        log.debug("Scanning {} invocations for function client calls", invocations.size());

        for (CtInvocation<?> invocation : invocations) {
            try {
                FunctionClientUsage usage = detectFunctionClientInvocation(invocation, functionIds, functionClassNames);
                if (usage != null) {
                    usages.add(usage);
                    log.debug("Found function client usage: {}.{}()",
                            usage.functionId(), usage.methodName());
                }
            } catch (Exception e) {
                log.warn("Error analyzing invocation at {}: {}",
                        getLocationString(invocation), e.getMessage());
            }
        }

        log.info("Found {} function client usages", usages.size());
        return usages;
    }

    /**
     * Detects a single function client invocation.
     */
    private FunctionClientUsage detectFunctionClientInvocation(
            CtInvocation<?> invocation,
            Set<String> functionIds,
            Set<String> functionClassNames) {

        // Check if this is one of our function methods
        String methodName = invocation.getExecutable().getSimpleName();
        if (!FUNCTION_METHODS.contains(methodName)) {
            return null;
        }

        // Check if the target is a function class
        // Pattern: SomethingFunction.instance().execute()
        CtExpression<?> target = invocation.getTarget();
        if (target == null) {
            return null;
        }

        // Check if target is a method call (likely .instance())
        String functionId = null;
        if (target instanceof CtInvocation<?> targetInvocation) {
            // Check if it's .instance() call
            if ("instance".equals(targetInvocation.getExecutable().getSimpleName())) {
                CtExpression<?> instanceTarget = targetInvocation.getTarget();
                if (instanceTarget != null) {
                    CtTypeReference<?> typeRef = null;

                    // Handle CtTypeAccess (e.g., MyTestFnFunction.instance())
                    if (instanceTarget instanceof CtTypeAccess<?> typeAccess) {
                        typeRef = typeAccess.getAccessedType();
                    } else {
                        // Regular expression type
                        typeRef = instanceTarget.getType();
                    }
                    if (typeRef != null) {
                        String typeName = typeRef.getSimpleName();
                        // Check if it matches our function class names
                        if (functionClassNames.contains(typeName)) {
                            // Extract function ID (remove "Function" suffix)
                            functionId = typeName.substring(0, typeName.length() - "Function".length());

                            // Verify it's in our function IDs
                            if (!functionIds.contains(functionId)) {
                                functionId = null;
                            }
                        }
                    }
                }
            }
        } else {
            // Could be a stored reference: funcClient.execute()
            // Check the type of the target
            CtTypeReference<?> targetType = target.getType();
            if (targetType != null) {
                String typeName = targetType.getSimpleName();
                if (functionClassNames.contains(typeName)) {
                    functionId = typeName.substring(0, typeName.length() - "Function".length());
                    if (!functionIds.contains(functionId)) {
                        functionId = null;
                    }
                }
            }
        }

        if (functionId == null) {
            return null;
        }

        // Verify package name
        CtTypeReference<?> declaringType = invocation.getExecutable().getDeclaringType();
        if (declaringType != null && declaringType.getQualifiedName() != null) {
            if (!declaringType.getQualifiedName().startsWith(FUNCTION_PACKAGE_PATTERN)) {
                return null;
            }
        }

        // Extract input type
        String inputType = extractInputType(invocation);

        // Check for scheduling (executeAsyncOnOrAfter)
        boolean hasScheduling = "executeAsyncOnOrAfter".equals(methodName);
        String schedulingExpression = null;
        if (hasScheduling && invocation.getArguments().size() >= 2) {
            schedulingExpression = extractSchedulingExpression(invocation.getArguments().get(1));
        }

        // Build source location
        SourceLocation location = buildSourceLocation(invocation);

        // Build call chain
        List<CallChainEntry> callChain = buildCallChain(invocation);

        return new FunctionClientUsage(
                functionId,
                methodName,
                location,
                callChain,
                inputType,
                hasScheduling,
                schedulingExpression
        );
    }

    /**
     * Extracts the input type from a function client invocation.
     */
    private String extractInputType(CtInvocation<?> invocation) {
        if (invocation.getArguments().isEmpty()) {
            return "void";
        }

        CtExpression<?> firstArg = invocation.getArguments().get(0);
        CtTypeReference<?> argType = firstArg.getType();

        if (argType == null) {
            return "Unknown";
        }

        String typeName = argType.getQualifiedName();

        // Check if it's String or a model class
        if ("java.lang.String".equals(typeName)) {
            return "String";
        }

        // Return fully qualified name for model classes
        return typeName;
    }

    /**
     * Extracts scheduling expression from LocalDateTime argument.
     */
    private String extractSchedulingExpression(CtExpression<?> scheduleArg) {
        if (scheduleArg == null) {
            return null;
        }

        // Try to get a readable representation
        String expression = scheduleArg.toString();

        // Limit length for readability
        if (expression.length() > 100) {
            expression = expression.substring(0, 97) + "...";
        }

        return expression;
    }

    /**
     * Detects all service usages in the model.
     * <p>
     * Pattern: Any usage of classes from dev.myorg.services.<serviceId>.*
     * Types: instantiation, method calls, constructor, static methods
     */
    public List<ServiceUsage> detectServiceUsages(Set<String> serviceIds) {
        log.info("Detecting service usages for {} services", serviceIds.size());
        List<ServiceUsage> usages = new ArrayList<>();

        if (serviceIds.isEmpty()) {
            log.debug("No service IDs to detect");
            return usages;
        }

        // Build service package patterns
        Map<String, String> servicePackages = new HashMap<>();
        for (String serviceId : serviceIds) {
            // MDZ017J -> dev.myorg.services.mdz017j
            String packageName = SERVICE_PACKAGE_PATTERN + "." + serviceId.toLowerCase();
            servicePackages.put(serviceId, packageName);
        }

        // Detect different usage types
        usages.addAll(detectServiceInstantiations(servicePackages));
        usages.addAll(detectServiceMethodCalls(servicePackages));
        usages.addAll(detectServiceStaticCalls(servicePackages));

        log.info("Found {} service usages", usages.size());
        return usages;
    }

    /**
     * Detects service instantiations: new ServiceClass()
     */
    private List<ServiceUsage> detectServiceInstantiations(Map<String, String> servicePackages) {
        List<ServiceUsage> usages = new ArrayList<>();
        List<CtConstructorCall<?>> constructorCalls = model.getElements(new TypeFilter<>(CtConstructorCall.class));

        log.debug("Scanning {} constructor calls for service instantiations", constructorCalls.size());

        for (CtConstructorCall<?> call : constructorCalls) {
            try {
                CtTypeReference<?> type = call.getType();
                if (type == null || type.getQualifiedName() == null) {
                    continue;
                }

                String qualifiedName = type.getQualifiedName();

                // Check if this matches any service package
                for (Map.Entry<String, String> entry : servicePackages.entrySet()) {
                    String serviceId = entry.getKey();
                    String packageName = entry.getValue();

                    if (qualifiedName.startsWith(packageName + ".")) {
                        ServiceUsage usage = new ServiceUsage(
                                serviceId,
                                packageName,
                                UsageType.INSTANTIATION,
                                buildSourceLocation(call),
                                buildCallChain(call),
                                type.getSimpleName(),
                                null // No method for instantiation
                        );
                        usages.add(usage);
                        log.debug("Found service instantiation: {} - {}", serviceId, type.getSimpleName());
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Error analyzing constructor call at {}: {}",
                        getLocationString(call), e.getMessage());
            }
        }

        return usages;
    }

    /**
     * Detects service method calls: instance.method()
     */
    private List<ServiceUsage> detectServiceMethodCalls(Map<String, String> servicePackages) {
        List<ServiceUsage> usages = new ArrayList<>();
        List<CtInvocation<?>> invocations = model.getElements(new TypeFilter<>(CtInvocation.class));

        log.debug("Scanning {} invocations for service method calls", invocations.size());

        for (CtInvocation<?> invocation : invocations) {
            try {
                CtExpression<?> target = invocation.getTarget();
                if (target == null) {
                    continue;
                }

                CtTypeReference<?> targetType = target.getType();
                if (targetType == null || targetType.getQualifiedName() == null) {
                    continue;
                }

                String qualifiedName = targetType.getQualifiedName();

                // Check if this matches any service package
                for (Map.Entry<String, String> entry : servicePackages.entrySet()) {
                    String serviceId = entry.getKey();
                    String packageName = entry.getValue();

                    if (qualifiedName.startsWith(packageName + ".")) {
                        ServiceUsage usage = new ServiceUsage(
                                serviceId,
                                packageName,
                                UsageType.METHOD_CALL,
                                buildSourceLocation(invocation),
                                buildCallChain(invocation),
                                targetType.getSimpleName(),
                                invocation.getExecutable().getSimpleName()
                        );
                        usages.add(usage);
                        log.debug("Found service method call: {}.{}()",
                                serviceId, invocation.getExecutable().getSimpleName());
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Error analyzing invocation at {}: {}",
                        getLocationString(invocation), e.getMessage());
            }
        }

        return usages;
    }

    /**
     * Detects service static method calls: ServiceClass.staticMethod()
     */
    private List<ServiceUsage> detectServiceStaticCalls(Map<String, String> servicePackages) {
        List<ServiceUsage> usages = new ArrayList<>();
        List<CtInvocation<?>> invocations = model.getElements(new TypeFilter<>(CtInvocation.class));

        log.debug("Scanning {} invocations for service static calls", invocations.size());

        for (CtInvocation<?> invocation : invocations) {
            try {
                // Static calls have null target
                if (invocation.getTarget() != null) {
                    continue;
                }

                CtExecutableReference<?> executable = invocation.getExecutable();
                if (executable == null) {
                    continue;
                }

                CtTypeReference<?> declaringType = executable.getDeclaringType();
                if (declaringType == null || declaringType.getQualifiedName() == null) {
                    continue;
                }

                String qualifiedName = declaringType.getQualifiedName();

                // Check if this matches any service package
                for (Map.Entry<String, String> entry : servicePackages.entrySet()) {
                    String serviceId = entry.getKey();
                    String packageName = entry.getValue();

                    if (qualifiedName.startsWith(packageName + ".")) {
                        ServiceUsage usage = new ServiceUsage(
                                serviceId,
                                packageName,
                                UsageType.STATIC_METHOD,
                                buildSourceLocation(invocation),
                                buildCallChain(invocation),
                                declaringType.getSimpleName(),
                                executable.getSimpleName()
                        );
                        usages.add(usage);
                        log.debug("Found service static call: {}.{}()",
                                serviceId, executable.getSimpleName());
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Error analyzing static invocation at {}: {}",
                        getLocationString(invocation), e.getMessage());
            }
        }

        return usages;
    }

    /**
     * Detects EDA publish calls.
     * <p>
     * Pattern: evntPub.publishEvent("<topic-name>", msgData)
     * Type: IEventPublisher from dev.myorg.mysection.eda.publisher.service.IEventPublisher
     */
    public List<EdaPublishUsage> detectEdaPublishUsages() {
        log.info("Detecting EDA publish usages");
        List<EdaPublishUsage> usages = new ArrayList<>();

        List<CtInvocation<?>> invocations = model.getElements(new TypeFilter<>(CtInvocation.class));
        log.debug("Scanning {} invocations for EDA publish calls", invocations.size());

        for (CtInvocation<?> invocation : invocations) {
            try {
                // Check if method name is publishEvent
                if (!"publishEvent".equals(invocation.getExecutable().getSimpleName())) {
                    continue;
                }

                // Check if target is IEventPublisher
                CtExpression<?> target = invocation.getTarget();
                if (target == null) {
                    continue;
                }

                CtTypeReference<?> targetType = target.getType();
                if (targetType == null || targetType.getQualifiedName() == null) {
                    continue;
                }

                // Check if it's IEventPublisher or implements it
                if (!isEventPublisher(targetType)) {
                    continue;
                }

                // Must have at least 2 arguments (topic, messageData)
                List<CtExpression<?>> args = invocation.getArguments();
                if (args.size() < 2) {
                    continue;
                }

                // Extract topic name (first argument)
                TopicResolution topicResolution = resolveTopicName(args.get(0));

                // Extract message data type (second argument)
                String messageDataType = extractMessageDataType(args.get(1));

                EdaPublishUsage usage = new EdaPublishUsage(
                        topicResolution.topicName,
                        topicResolution.status,
                        topicResolution.variableType,
                        buildSourceLocation(invocation),
                        buildCallChain(invocation),
                        messageDataType
                );

                usages.add(usage);
                log.debug("Found EDA publish: topic={}, status={}",
                        topicResolution.topicName, topicResolution.status);

            } catch (Exception e) {
                log.warn("Error analyzing EDA publish at {}: {}",
                        getLocationString(invocation), e.getMessage());
            }
        }

        log.info("Found {} EDA publish usages", usages.size());
        return usages;
    }

    /**
     * Checks if a type is or implements IEventPublisher.
     */
    private boolean isEventPublisher(CtTypeReference<?> typeRef) {
        if (typeRef == null) {
            return false;
        }

        String qualifiedName = typeRef.getQualifiedName();
        if (qualifiedName == null) {
            return false;
        }

        // Direct match
        if (EDA_PUBLISHER_TYPE.equals(qualifiedName)) {
            return true;
        }

        // Check if it implements the interface
        try {
            if (typeRef.getTypeDeclaration() != null) {
                Set<CtTypeReference<?>> superInterfaces = typeRef.getTypeDeclaration().getSuperInterfaces();
                for (CtTypeReference<?> superInterface : superInterfaces) {
                    if (EDA_PUBLISHER_TYPE.equals(superInterface.getQualifiedName())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not check interfaces for type: {}", qualifiedName);
        }

        return false;
    }

    /**
     * Resolves the topic name from the first argument of publishEvent.
     */
    private TopicResolution resolveTopicName(CtExpression<?> topicArg) {
        if (topicArg instanceof CtLiteral<?> literal) {
            // String literal - direct extraction
            Object value = literal.getValue();
            if (value instanceof String topicName) {
                return new TopicResolution(topicName, TopicResolutionStatus.RESOLVED, null);
            }
        } else if (topicArg instanceof CtVariableRead<?> variableRead) {
            // Variable reference - try to resolve
            String topicName = tryResolveVariable(variableRead);
            if (topicName != null) {
                return new TopicResolution(topicName, TopicResolutionStatus.RESOLVED, null);
            } else {
                // Could not resolve - mark as unknown
                String varType = variableRead.getType() != null ?
                        variableRead.getType().getQualifiedName() : "Unknown";
                return new TopicResolution(
                        variableRead.getVariable().getSimpleName(),
                        TopicResolutionStatus.UNKNOWN_VARIABLE,
                        varType
                );
            }
        }

        // Complex expression
        String expressionType = topicArg.getType() != null ?
                topicArg.getType().getQualifiedName() : "Unknown";
        return new TopicResolution(
                topicArg.toString(),
                TopicResolutionStatus.UNKNOWN_COMPLEX,
                expressionType
        );
    }

    /**
     * Attempts to resolve a variable to its string constant value.
     */
    private String tryResolveVariable(CtVariableRead<?> variableRead) {
        try {
            var variable = variableRead.getVariable().getDeclaration();
            if (variable instanceof CtField<?> field) {
                CtExpression<?> defaultExpression = field.getDefaultExpression();
                if (defaultExpression instanceof CtLiteral<?> literal) {
                    Object value = literal.getValue();
                    if (value instanceof String stringValue) {
                        return stringValue;
                    }
                }

            } else if (variable instanceof CtLocalVariable<?> localVar) {
                // Local variable - check if initialized with a string literal
                CtExpression<?> defaultExpression = localVar.getDefaultExpression();
                if (defaultExpression instanceof CtLiteral<?> literal) {
                    Object value = literal.getValue();
                    if (value instanceof String stringValue) {
                        return stringValue;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve variable: {}", variableRead.getVariable().getSimpleName());
        }

        return null;
    }

    /**
     * Extracts the MessageData type from the second argument.
     */
    private String extractMessageDataType(CtExpression<?> msgDataArg) {
        if (msgDataArg == null) {
            return null;
        }

        CtTypeReference<?> type = msgDataArg.getType();
        if (type == null) {
            return null;
        }

        return type.getQualifiedName();
    }

    /**
     * Builds a SourceLocation from a Spoon element.
     */
    private SourceLocation buildSourceLocation(CtElement element) {
        String filePath = "unknown";
        String className = "unknown";
        String methodName = "unknown";
        int lineNumber = -1;

        try {
            // Get file path
            if (element.getPosition() != null && element.getPosition().getFile() != null) {
                filePath = element.getPosition().getFile().getPath();
                lineNumber = element.getPosition().getLine();
            }

            // Get class name
            CtClass<?> parentClass = element.getParent(CtClass.class);
            if (parentClass != null) {
                className = parentClass.getQualifiedName();
            }

            // Get method name
            CtMethod<?> parentMethod = element.getParent(CtMethod.class);
            if (parentMethod != null) {
                methodName = parentMethod.getSimpleName();
            }
        } catch (Exception e) {
            log.debug("Could not extract full location information: {}", e.getMessage());
        }

        return new SourceLocation(filePath, className, methodName, lineNumber);
    }

    /**
     * Builds call chain(s) from a usage element using CallGraphWalker.
     * Returns the first (primary) call chain, or empty list if none found.
     */
    private List<CallChainEntry> buildCallChain(CtElement element) {
        try {
            List<List<CallChainEntry>> allChains = callGraphWalker.buildCallChains(element);

            if (allChains.isEmpty()) {
                return List.of();
            }

            // Return the first (typically shortest or most relevant) chain
            // In the future, we could handle multiple chains differently
            List<CallChainEntry> primaryChain = allChains.get(0);

            if (allChains.size() > 1) {
                log.debug("Multiple call chains found ({} total), using primary chain",
                        allChains.size());
            }

            return primaryChain;
        } catch (Exception e) {
            log.warn("Failed to build call chain: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Gets a location string for logging.
     */
    private String getLocationString(CtElement element) {
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
     * Helper record for topic resolution results.
     */
    private record TopicResolution(
            String topicName,
            TopicResolutionStatus status,
            String variableType
    ) {
    }
}