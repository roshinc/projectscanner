package dev.roshin.projectscanner.analysis.spoon;

import dev.roshin.projectscanner.analysis.pom.PomAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtTypeReference;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class SmartServiceDetector {

    private static final Logger log = LoggerFactory.getLogger(SmartServiceDetector.class);
    private static final String SMART_SERVICE_ANNOT = "dev.myorg.mysection.smart.SmartService";
    private static final String UISERVICE_ANNOT = "dev.myorg.mysection.smart.UIService";
    private static final String FUNCTION_ANNOT = "dev.myorg.mysection.smart.Function";
    private static final String ISERVICE_INTERFACE = "dev.myorg.mysection.smart.IService";

    public SmartServiceInfo analyze(CtModel model, PomAnalyzer pomAnalyzer) {

        String artifactId = pomAnalyzer.getArtifactId(); // e.g., XTSTCLFNJ
        log.info("SmartServiceDetector analyzing for SmartService({})", artifactId);

        // 1. Find the interface annotated with @SmartService("<artifactId>")
        CtInterface<?> smartServiceInterface = findSmartServiceInterface(model, artifactId);
        if (smartServiceInterface == null) {
            log.warn("No @SmartService(\"{}\") interface found", artifactId);
            return null;
        }

        log.info("Found SmartService interface: {}", smartServiceInterface.getQualifiedName());

        // 2. Detect whether this is a "UI service"
        boolean isUiService = isUiService(model, smartServiceInterface);

        // 3. If regular service, extract @Function metadata
        Map<String, FunctionMetadata> fnMap =
                isUiService ? Collections.emptyMap() : extractFunctionMetadata(smartServiceInterface);

        return new SmartServiceInfo(
                artifactId,
                isUiService,
                smartServiceInterface,
                fnMap
        );
    }

    // ----------------------------------------------------------
    // 1. Locate @SmartService("<artifactId>") interface
    // ----------------------------------------------------------
    private CtInterface<?> findSmartServiceInterface(CtModel model, String serviceId) {
        for (CtType<?> type : model.getAllTypes()) {
            if (!(type instanceof CtInterface<?> iface)) continue;

            Optional<CtAnnotation<?>> smartAnn = type.getAnnotations().stream()
                    .filter(a -> a.getAnnotationType().getQualifiedName().equals(SMART_SERVICE_ANNOT))
                    .findFirst();

            if (smartAnn.isEmpty()) continue;

            Object val = smartAnn.get().getValue("value");
            if (val instanceof CtLiteral<?> literal) {
                Object value = literal.getValue();
                if (value instanceof String annotaionValue) {
                    if (annotaionValue.equalsIgnoreCase(serviceId)) {
                        return iface;
                    }
                }
            }
        }
        return null;
    }

    // ----------------------------------------------------------
    // 2. Determine UI service
    // ----------------------------------------------------------
    private boolean isUiService(CtModel model, CtInterface<?> iface) {

        // Case A: Annotation directly on interface
        boolean ifaceHasUi = iface.getAnnotations().stream()
                .anyMatch(a -> a.getAnnotationType().getQualifiedName().equals(UISERVICE_ANNOT));

        if (ifaceHasUi) {
            log.info("@UIService detected directly on interface");
            return true;
        }

        // Case B: Implementation class has @UIService
        for (CtType<?> t : model.getAllTypes()) {
            if (!(t instanceof CtClass<?> cls)) continue;

            boolean implementsIface = cls.getSuperInterfaces().stream()
                    .map(CtTypeReference::getQualifiedName)
                    .anyMatch(n -> n.equals(iface.getQualifiedName()));

            if (!implementsIface) continue;

            boolean hasUiAnnot = cls.getAnnotations().stream()
                    .anyMatch(a -> a.getAnnotationType().getQualifiedName().equals(UISERVICE_ANNOT));

            if (hasUiAnnot) {
                log.info("@UIService detected on implementing class {}", cls.getQualifiedName());
                return true;
            }
        }

        return false;
    }

    // ----------------------------------------------------------
    // 3. Extract method-level @Function metadata
    // ----------------------------------------------------------
    private Map<String, FunctionMetadata> extractFunctionMetadata(CtInterface<?> iface) {

        Map<String, FunctionMetadata> results = new LinkedHashMap<>();

        for (CtMethod<?> method : iface.getMethods()) {

            Optional<CtAnnotation<?>> fnAnn = method.getAnnotations().stream()
                    .filter(a -> a.getAnnotationType().getQualifiedName().equals(FUNCTION_ANNOT))
                    .findFirst();

            if (fnAnn.isEmpty()) continue; // Only functions with @Function

            CtAnnotation<?> a = fnAnn.get();

            FunctionMetadata meta = new FunctionMetadata(
                    Optional.ofNullable(a.getValue("id")).map(Object::toString).orElse(method.getSimpleName()),
                    Optional.ofNullable(a.getValue("name")).map(Object::toString).orElse(method.getSimpleName())
            );

            String fullSig = buildFullyQualifiedMethodSignature(method);
            results.put(fullSig, meta);
        }

        return results;
    }

    private String buildFullyQualifiedMethodSignature(CtMethod<?> method) {
        String className = method.getDeclaringType().getQualifiedName();
        String methodName = method.getSimpleName();

        String params = method.getParameters().stream()
                .map(p -> p.getType().getQualifiedName())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        return className + "." + methodName + "(" + params + ")";
    }


    public record SmartServiceInfo(
            String serviceId,
            boolean isUiService,
            CtInterface<?> interfaceDecl,
            Map<String, FunctionMetadata> functionMethods
    ) {
    }

    public record FunctionMetadata(
            String id,
            String name
    ) {
    }
}
