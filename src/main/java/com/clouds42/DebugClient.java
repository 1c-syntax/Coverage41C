package com.clouds42;

import com._1c.g5.v8.dt.debug.core.model.BslModuleReference;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePath;
import com._1c.g5.v8.dt.debug.core.model.values.BslValuePathItem;
import com._1c.g5.v8.dt.debug.core.runtime.client.RuntimeDebugClientException;
import com._1c.g5.v8.dt.debug.core.runtime.client.RuntimeEvaluationRequest;
import com._1c.g5.v8.dt.debug.model.area.DebugAreaInfo;
import com._1c.g5.v8.dt.debug.model.attach.AttachFactory;
import com._1c.g5.v8.dt.debug.model.attach.DebugAutoAttachSettings;
import com._1c.g5.v8.dt.debug.model.base.data.*;
import com._1c.g5.v8.dt.debug.model.breakpoints.BPWorkspaceInternal;
import com._1c.g5.v8.dt.debug.model.breakpoints.BreakpointInfo;
import com._1c.g5.v8.dt.debug.model.breakpoints.BreakpointsFactory;
import com._1c.g5.v8.dt.debug.model.breakpoints.ModuleBPInfoInternal;
import com._1c.g5.v8.dt.debug.model.calculations.*;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmdInfoBase;
import com._1c.g5.v8.dt.debug.model.rdbg.request.response.*;
import com._1c.g5.v8.dt.debug.model.rte.filter.FilterFactory;
import com._1c.g5.v8.dt.debug.model.rte.filter.RteFilterItem;
import com._1c.g5.v8.dt.debug.model.rte.filter.RteFilterStorage;
import com._1c.g5.v8.dt.internal.debug.core.model.RuntimePresentationConverter;
import com._1c.g5.v8.dt.internal.debug.core.model.breakpoints.BslLineBreakpointInformation;
import com._1c.g5.v8.dt.internal.debug.core.model.breakpoints.BslModuleInformation;
import com._1c.g5.v8.dt.internal.debug.core.runtime.client.RuntimeDebugModelXmlSerializer;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.xml.type.SimpleAnyType;
import org.eclipse.emf.ecore.xml.type.XMLTypeFactory;
import org.eclipse.emf.ecore.xml.type.XMLTypePackage;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

public class DebugClient extends AbstractDebugClient{
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final HttpClient httpClient = this.createHttpClient();
    private UUID debugServerUuid;
    private String infobaseAlias;
    private String debugComponentUrl;

    @Inject
    public DebugClient(RuntimeDebugModelXmlSerializer serializer) {
        super(serializer);

        try {
            this.httpClient.start();
        } catch (Exception var3) {
            throw new IllegalStateException("Initialization error", var3);
        }
    }

    public void configure(String debugServerUrl, UUID debugServerUuid, String infobaseAlias) {
        if (Strings.isNullOrEmpty(debugServerUrl)) {
            throw new IllegalArgumentException(String.format("Provided url '%s' is inconnrect", debugServerUrl));
        } else if (Strings.isNullOrEmpty(infobaseAlias)) {
            throw new IllegalArgumentException(String.format("Provided infobase alias '%s' is inconnrect", infobaseAlias));
        } else {
            this.debugServerUuid = debugServerUuid;
            this.debugComponentUrl = this.getComponentUrl(debugServerUrl, "e1crdbg/rdbg");
            this.infobaseAlias = infobaseAlias;
            logger.info(String.format("Configured 1C:Enterprise Runtime debug HTTP client: %s : %s : %s", debugServerUrl, debugServerUuid, this.infobaseAlias));
        }
    }

    public void dispose() throws RuntimeDebugClientException {
        if (!this.httpClient.isStopping() || !this.httpClient.isStopped()) {
            try {
                this.httpClient.stop();
            } catch (Exception var2) {
                throw new RuntimeDebugClientException(var2);
            }
        }

    }

    public AttachDebugUIResult connect(String password) throws RuntimeDebugClientException {
        RDBGAttachDebugUIRequest requestContent = ResponseFactory.eINSTANCE.createRDBGAttachDebugUIRequest();
        requestContent.setCredentials(RuntimePresentationConverter.fromPresentation(password));
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "attachDebugUI");
        RDBGAttachDebugUIResponse responseContent = AbstractDebugClient.performRuntimeHttpRequest(this, request, this.initRequest(requestContent), RDBGAttachDebugUIResponse.class);
        assert responseContent != null;
        return responseContent.getResult();
    }

    public boolean disconnect() throws RuntimeDebugClientException {
        RDBGDetachDebugUIRequest requestContent = ResponseFactory.eINSTANCE.createRDBGDetachDebugUIRequest();
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "detachDebugUI");
        RDBGDetachDebugUIResponse responseContent = AbstractDebugClient.performRuntimeHttpRequest(this, request, this.initRequest(requestContent), RDBGDetachDebugUIResponse.class);
        assert responseContent != null;
        return responseContent.isResult();
    }

    public void initSettings(boolean suspend) throws RuntimeDebugClientException {
        RDBGSetInitialDebugSettingsRequest requestContent = ResponseFactory.eINSTANCE.createRDBGSetInitialDebugSettingsRequest();
        if (suspend) {
            HTTPServerInitialDebugSettingsData settingsData = ResponseFactory.eINSTANCE.createHTTPServerInitialDebugSettingsData();
            requestContent.setData(settingsData);
        }

        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "initSettings");
        this.performRuntimeHttpRequest(request, this.initRequest(requestContent));
    }

    public void attachRuntimeDebugTargets(Collection<UUID> debugTarget) throws RuntimeDebugClientException {
        this.performRuntimeDebugTargetAttachDetach(debugTarget, true);
    }

    public void detachRuntimeDebugTargets(Collection<UUID> debugTarget) throws RuntimeDebugClientException {
        this.performRuntimeDebugTargetAttachDetach(debugTarget, false);
    }

    public List<DbgTargetStateInfo> resume(DebugTargetId debugTarget) throws RuntimeDebugClientException {
        return this.performStepAction(debugTarget, DebugStepAction.CONTINUE);
    }

    public DbgTargetState getState(DebugTargetId debugTarget) throws RuntimeDebugClientException {
        RDBGGetDbgTargetStateRequest requestContent = ResponseFactory.eINSTANCE.createRDBGGetDbgTargetStateRequest();
        requestContent.setId(this.buildDebugTargetIdLight(debugTarget));
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "getDbgTargetState");
        RDBGGetDbgTargetStateResponse responseContent = AbstractDebugClient.performRuntimeHttpRequest(this, request, this.initRequest(requestContent), RDBGGetDbgTargetStateResponse.class);
        assert responseContent != null;
        return responseContent.getState();
    }

    public void suspend() throws RuntimeDebugClientException {
        RDBGSetBreamOnNextStatementRequest requestContent = ResponseFactory.eINSTANCE.createRDBGSetBreamOnNextStatementRequest();
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "setBreakOnNextStatement");
        this.performRuntimeHttpRequest(request, this.initRequest(requestContent));
    }

    public List<DbgTargetStateInfo> stepInto(DebugTargetId debugTarget) throws RuntimeDebugClientException {
        return this.performStepAction(debugTarget, DebugStepAction.STEP_IN);
    }

    public List<DbgTargetStateInfo> stepOver(DebugTargetId debugTarget) throws RuntimeDebugClientException {
        return this.performStepAction(debugTarget, DebugStepAction.STEP);
    }

    public List<DbgTargetStateInfo> stepReturn(DebugTargetId debugTarget) throws RuntimeDebugClientException {
        return this.performStepAction(debugTarget, DebugStepAction.STEP_OUT);
    }

    public void terminateRuntimeDebugTargets(Collection<DebugTargetId> debugTargets) throws RuntimeDebugClientException {
        RDBGTerminateRequest requestContent = ResponseFactory.eINSTANCE.createRDBGTerminateRequest();
        requestContent.getTargetID().addAll(debugTargets);
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "terminateDbgTarget");
        this.performRuntimeHttpRequest(request, this.initRequest(requestContent));
    }

    public List<? extends DBGUIExtCmdInfoBase> ping() throws RuntimeDebugClientException {
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "pingDebugUIParams").param("dbgui", this.debugServerUuid.toString());
        RDBGPingDebugUIResponse responseContent = this.performRuntimeHttpRequest(request, RDBGPingDebugUIResponse.class);
        return  (responseContent == null ? Collections.emptyList() : responseContent.getResult());
    }

    public void setLineBreakpoints(Map<BslModuleInformation, List<BslLineBreakpointInformation>> modulesBreakpoints) throws RuntimeDebugClientException {
        RDBGSetBreakpointsRequest requestContent = ResponseFactory.eINSTANCE.createRDBGSetBreakpointsRequest();
        BPWorkspaceInternal breakpointsWorkspace = BreakpointsFactory.eINSTANCE.createBPWorkspaceInternal();

        for (Map.Entry<BslModuleInformation, List<BslLineBreakpointInformation>> bslModuleInformationListEntry : modulesBreakpoints.entrySet()) {

            BslModuleReference reference = bslModuleInformationListEntry.getKey().getReference();
            ModuleBPInfoInternal breakpointsModuleInfo = BreakpointsFactory.eINSTANCE.createModuleBPInfoInternal();
            breakpointsModuleInfo.setId(this.createBslModuleId(bslModuleInformationListEntry.getKey(), reference));

            BreakpointInfo breakpointInfoModel;
            for (Iterator<BslLineBreakpointInformation> var9 = bslModuleInformationListEntry.getValue().iterator();
                 var9.hasNext(); breakpointsModuleInfo.getBpInfo().add(breakpointInfoModel)) {
                BslLineBreakpointInformation breakpointInfo = var9.next();
                breakpointInfoModel = BreakpointsFactory.eINSTANCE.createBreakpointInfo();
                breakpointInfoModel.setLine(new BigDecimal(breakpointInfo.getLineNumber()));
                if (breakpointInfo.getCondition() != null) {
                    breakpointInfoModel.setCondition(breakpointInfo.getCondition());
                }
            }

            breakpointsWorkspace.getModuleBPInfo().add(breakpointsModuleInfo);
        }

        requestContent.setBpWorkspace(breakpointsWorkspace);
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "setBreakpoints");
        this.performRuntimeHttpRequest(request, this.initRequest(requestContent));
    }

    public void setExceptionBreakpoints(boolean enabled, Iterable<String> exceptions) throws RuntimeDebugClientException {
        RDBGSetRunTimeErrorProcessingRequest requestContent = ResponseFactory.eINSTANCE.createRDBGSetRunTimeErrorProcessingRequest();
        RteFilterStorage filter = FilterFactory.eINSTANCE.createRteFilterStorage();
        filter.setStopOnErrors(enabled);
        filter.setAnalyzeErrorStr(exceptions != null);
        if (exceptions != null) {

            for (String errorTemplate : exceptions) {
                RteFilterItem item = FilterFactory.eINSTANCE.createRteFilterItem();
                item.setInclude(true);
                item.setStr(errorTemplate);
                filter.getStrTemplate().add(item);
            }
        }

        requestContent.setState(filter);
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "setBreakOnRTE");
        this.performRuntimeHttpRequest(request, this.initRequest(requestContent));
    }

    public List<DebugTargetId> getRuntimeDebugTargets(String debugAreaName) throws RuntimeDebugClientException {
        RDBGSGetDbgTargetsRequest requestContent = ResponseFactory.eINSTANCE.createRDBGSGetDbgTargetsRequest();
        if (debugAreaName != null) {
            requestContent.setDegugAreaName(debugAreaName);
        }

        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "getDbgTargets");
        RDBGSGetDbgTargetsResponse responseContent = AbstractDebugClient.performRuntimeHttpRequest(this, request, this.initRequest(requestContent), RDBGSGetDbgTargetsResponse.class);
        assert responseContent != null;
        return responseContent.getId();
    }

    public List<StackItemViewInfoData> getRuntimeTargetStackFrames(DebugTargetId debugTarget) throws RuntimeDebugClientException {
        RDBGGetCallStackRequest requestContent = ResponseFactory.eINSTANCE.createRDBGGetCallStackRequest();
        requestContent.setId(this.buildDebugTargetIdLight(debugTarget));
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "getCallStack");
        RDBGGetCallStackResponse responseContent = AbstractDebugClient.performRuntimeHttpRequest(this, request, this.initRequest(requestContent), RDBGGetCallStackResponse.class);
        return responseContent == null ? Collections.emptyList() : Lists.reverse(responseContent.getCallStack());
    }

    public CalculationResultBaseData evaluateExpression(DebugTargetId debugTarget, int waitTime, RuntimeEvaluationRequest request) throws RuntimeDebugClientException {
        Collection<CalculationResultBaseData> result = this.evaluateExpressions(debugTarget, waitTime, Collections.singleton(request));
        return result != null && !result.isEmpty() ? result.iterator().next() : null;
    }

    public Collection<CalculationResultBaseData> evaluateExpressions(DebugTargetId debugTarget, int waitTime, Collection<RuntimeEvaluationRequest> requests) throws RuntimeDebugClientException {
        RDBGEvalExprRequest requestContent = ResponseFactory.eINSTANCE.createRDBGEvalExprRequest();
        requestContent.setCalcWaitingTime(new BigDecimal(waitTime));
        requestContent.setTargetID(this.buildDebugTargetIdLight(debugTarget));
        requestContent.getExpr().addAll(requests.stream().map(this::buildCalculationSourceDataStorage).collect(Collectors.toList()));
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "evalExpr");
        RDBGEvalExprResponse responseContent = AbstractDebugClient.performRuntimeHttpRequest(this, request, this.initRequest(requestContent), RDBGEvalExprResponse.class);
        return responseContent != null && !responseContent.getResult().isEmpty() ? responseContent.getResult() : null;
    }

    public CalculationResultBaseData evaluateVariables(DebugTargetId debugTarget, int stackLevel, int maxSize, boolean isMultiLine, int waitTime, UUID expressionUuid, UUID evaluationUuid) throws RuntimeDebugClientException {
        RDBGEvalLocalVariablesRequest requestContent = ResponseFactory.eINSTANCE.createRDBGEvalLocalVariablesRequest();
        requestContent.setCalcWaitingTime(new BigDecimal(waitTime));
        requestContent.setTargetID(this.buildDebugTargetIdLight(debugTarget));
        CalculationSourceDataStorage toEvaluate = this.createCalculationSourceDataStorage(stackLevel, maxSize, isMultiLine);
        SourceCalculationDataInfo expressionInfo = CalculationsFactory.eINSTANCE.createSourceCalculationDataInfo();
        expressionInfo.setExpressionID(expressionUuid.toString());
        expressionInfo.setExpressionResultID(evaluationUuid.toString());
        expressionInfo.getInterfaces().add(ViewInterface.CONTEXT);
        toEvaluate.setSrcCalcInfo(expressionInfo);
        requestContent.getExpr().add(toEvaluate);
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "evalLocalVariables");
        RDBGEvalLocalVariablesResponse responseContent = AbstractDebugClient.performRuntimeHttpRequest(this, request, this.initRequest(requestContent), RDBGEvalLocalVariablesResponse.class);
        assert responseContent != null;
        return responseContent.getResult();
    }

    public <T> RDBGModifyValueResponse modifyExpressionTyped(BslValuePath expressionPath, T value, DebugTargetId debugTarget, int stackLevel, int maxSize, boolean isMultiLine, int waitTime, UUID expressionUuid, UUID evaluationUuid) throws RuntimeDebugClientException {
        RDBGModifyValueRequest requestContent = this.buildValueModificationRequest(expressionPath, debugTarget, stackLevel, maxSize, isMultiLine, waitTime, expressionUuid, evaluationUuid);
        NewValueInfo newValue = CalculationsFactory.eINSTANCE.createNewValueInfo();
        newValue.setValue(this.buildNewValue(value));
        newValue.setVariant(NewValueVariant.VAL);
        requestContent.setNewValueInfo(newValue);
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "modifyValue");
        return AbstractDebugClient.performRuntimeHttpRequest(this, request, this.initRequest(requestContent), RDBGModifyValueResponse.class);
    }

    public RDBGModifyValueResponse modifyExpression(BslValuePath expressionPath, String expression, DebugTargetId debugTarget, int stackLevel, int maxSize, boolean isMultiLine, int waitTime, UUID expressionUuid, UUID evaluationUuid) throws RuntimeDebugClientException {
        RDBGModifyValueRequest requestContent = this.buildValueModificationRequest(expressionPath, debugTarget, stackLevel, maxSize, isMultiLine, waitTime, expressionUuid, evaluationUuid);
        NewValueInfo newValue = CalculationsFactory.eINSTANCE.createNewValueInfo();
        newValue.setValueExpression(expression);
        newValue.setVariant(NewValueVariant.EXPR);
        requestContent.setNewValueInfo(newValue);
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "modifyValue");
        return AbstractDebugClient.performRuntimeHttpRequest(this, request, this.initRequest(requestContent), RDBGModifyValueResponse.class);
    }

    public void setDebugAreas(List<DebugAreaInfo> areas) throws RuntimeDebugClientException {
        RDBGSetListOfDebugAreaRequest requestContent = ResponseFactory.eINSTANCE.createRDBGSetListOfDebugAreaRequest();
        requestContent.getDebugAreaInfo().addAll(areas);
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "setListOfDebugArea");
        this.performRuntimeHttpRequest(request, this.initRequest(requestContent));
    }

    public List<DebugAreaInfo> getDebugAreas() throws RuntimeDebugClientException {
        RDBGGetListOfDebugAreaRequest requestContent = ResponseFactory.eINSTANCE.createRDBGGetListOfDebugAreaRequest();
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "getListOfDebugArea");
        RDBGGetListOfDebugAreaResponse responseContent = AbstractDebugClient.performRuntimeHttpRequest(this, request, this.initRequest(requestContent), RDBGGetListOfDebugAreaResponse.class);
        return (responseContent != null && responseContent.getDebugAreaInfo() != null ? responseContent.getDebugAreaInfo() : Lists.newArrayList());
    }

    public void setAutoconnectDebugTargets(List<String> debugAreaNames, List<DebugTargetType> debugTargets) throws RuntimeDebugClientException {
        RDBGSetAutoAttachSettingsRequest requestContent = ResponseFactory.eINSTANCE.createRDBGSetAutoAttachSettingsRequest();
        DebugAutoAttachSettings settings = AttachFactory.eINSTANCE.createDebugAutoAttachSettings();
        settings.getTargetType().addAll(debugTargets);
        settings.getAreaName().addAll(debugAreaNames);
        requestContent.setAutoAttachSettings(settings);
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "setAutoAttachSettings");
        this.performRuntimeHttpRequest(request, this.initRequest(requestContent));
    }

    public void toggleProfiling(UUID uuid) throws RuntimeDebugClientException {
        RDBGSetMeasureModeRequest requestContent = ResponseFactory.eINSTANCE.createRDBGSetMeasureModeRequest();
        if (uuid != null) {
            requestContent.setMeasureModeSeanceID(uuid.toString());
        }

        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "setMeasureMode");
        this.performRuntimeHttpRequest(request, this.initRequest(requestContent));
    }

    public boolean notifyInfobaseUpdateStart() throws RuntimeDebugClientException {
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "startUpdateIB");
        RDBGStartUpdateIBResponse responseContent = AbstractDebugClient.performRuntimeHttpRequest(this, request, this.initRequest(ResponseFactory.eINSTANCE.createRDBGStartUpdateIBRequest()), RDBGStartUpdateIBResponse.class);
        assert responseContent != null;
        return responseContent.isResult();
    }

    public boolean notifyInfobaseUpdateFinish() throws RuntimeDebugClientException {
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "finishUpdateIB");
        RDBGFinishUpdateIBResponse responseContent = AbstractDebugClient.performRuntimeHttpRequest(this, request, this.initRequest(ResponseFactory.eINSTANCE.createRDBGStartUpdateIBRequest()), RDBGFinishUpdateIBResponse.class);
        assert responseContent != null;
        return responseContent.isResult();
    }

    public void restartRuntimeDebugTarget(Collection<DebugTargetId> debugTargets) throws RuntimeDebugClientException {
        RDBGRestartRequest requestContent = ResponseFactory.eINSTANCE.createRDBGRestartRequest();
        requestContent.getTargetID().addAll(debugTargets);
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "restartDbgTarget");
        this.performRuntimeHttpRequest(request, this.initRequest(requestContent));
    }

    protected Request buildRequest(HttpMethod method, String componentUrl) {
        return super.buildRequest(this.httpClient, method, componentUrl);
    }

    protected RDbgBaseRequest initRequest(RDbgBaseRequest request) {
        request.setIdOfDebuggerUI(this.debugServerUuid.toString());
        request.setInfoBaseAlias(this.infobaseAlias);
        return request;
    }

    protected List<DbgTargetStateInfo> performStepAction(DebugTargetId debugTarget, DebugStepAction actionType) throws RuntimeDebugClientException {
        RDBGStepRequest requestContent = ResponseFactory.eINSTANCE.createRDBGStepRequest();
        requestContent.setAction(actionType);
        requestContent.setTargetID(this.buildDebugTargetIdLight(debugTarget));
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "step");
        RDBGStepResponse responseContent = AbstractDebugClient.performRuntimeHttpRequest(this, request, this.initRequest(requestContent), RDBGStepResponse.class);
        assert responseContent != null;
        return responseContent.getItem();
    }

    protected void performRuntimeDebugTargetAttachDetach(Collection<UUID> debugTargets, boolean attach) throws RuntimeDebugClientException {
        RDBGAttachDetachDebugTargetsRequest requestContent = ResponseFactory.eINSTANCE.createRDBGAttachDetachDebugTargetsRequest();
        requestContent.setAttach(attach);
        requestContent.getId().addAll(Collections2.transform(debugTargets, (input) -> {
            DebugTargetIdLight target = DataFactory.eINSTANCE.createDebugTargetIdLight();
            target.setId(input != null ? input.toString() : null);
            return target;
        }));
        Request request = this.buildRequest(HttpMethod.POST, this.debugComponentUrl).param("cmd", "attachDetachDbgTargets");
        this.performRuntimeHttpRequest(request, this.initRequest(requestContent));
    }

    protected SourceCalculationDataInfo buildExpressionPath(BslValuePath expressionPath, UUID expressionUuid, UUID evaluationUuid) {
        SourceCalculationDataInfo expressionInfo = CalculationsFactory.eINSTANCE.createSourceCalculationDataInfo();
        SourceCalculationDataItem expressionItem = CalculationsFactory.eINSTANCE.createSourceCalculationDataItem();
        expressionItem.setExpression(expressionPath.getExpression());
        expressionItem.setItemType(SourceCalculationDataItemType.EXPRESSION);
        expressionInfo.setExpressionID(expressionUuid.toString());
        expressionInfo.setExpressionResultID(evaluationUuid.toString());
        expressionInfo.getCalcItem().add(expressionItem);

        SourceCalculationDataItem propertyOrArrayItem;
        for(Iterator<BslValuePathItem<?>> var7 = expressionPath.getPropertiesAndIndexes().iterator(); var7.hasNext(); expressionInfo.getCalcItem().add(propertyOrArrayItem)) {
            BslValuePathItem<?> pathItem = var7.next();
            propertyOrArrayItem = CalculationsFactory.eINSTANCE.createSourceCalculationDataItem();
            if (pathItem.getItemClass() == String.class) {
                propertyOrArrayItem.setItemType(SourceCalculationDataItemType.PROPERTY);
                propertyOrArrayItem.setProperty((String)pathItem.getValue());
            } else if (pathItem.getItemClass() == Integer.class) {
                propertyOrArrayItem.setItemType(SourceCalculationDataItemType.INDEX);
                propertyOrArrayItem.setIndex(new BigDecimal((Integer)pathItem.getValue()));
            }
        }

        return expressionInfo;
    }

    protected RDBGModifyValueRequest buildValueModificationRequest(BslValuePath expressionPath, DebugTargetId debugTarget, int stackLevel, int maxSize, boolean isMultiLine, int waitTime, UUID expressionUuid, UUID evaluationUuid) {
        RDBGModifyValueRequest requestContent = ResponseFactory.eINSTANCE.createRDBGModifyValueRequest();
        requestContent.setTimeout(new BigDecimal(waitTime));
        requestContent.setTargetID(this.buildDebugTargetIdLight(debugTarget));
        CalculationSourceDataStorage toEvaluate = this.createCalculationSourceDataStorage(stackLevel, maxSize, isMultiLine);
        SourceCalculationDataInfo expressionInfo = this.buildExpressionPath(expressionPath, expressionUuid, evaluationUuid);
        toEvaluate.setSrcCalcInfo(expressionInfo);
        requestContent.setModifyDataPath(toEvaluate);
        return requestContent;
    }

    protected DebugTargetIdLight buildDebugTargetIdLight(DebugTargetId debugTarget) {
        DebugTargetIdLight debugTargetId = DataFactory.eINSTANCE.createDebugTargetIdLight();
        debugTargetId.setId(debugTarget.getId());
        return debugTargetId;
    }

    protected CalculationSourceDataStorage buildCalculationSourceDataStorage(RuntimeEvaluationRequest request) {
        CalculationSourceDataStorage storage = this.createCalculationSourceDataStorage(request.getStackLevel(), request.getMaxSize(), request.isMultiLine());
        SourceCalculationDataInfo expressionInfo = this.buildExpressionPath(request.getExpressionPath(), request.getExpressionUuid(), request.getEvaluationUuid());
        expressionInfo.getInterfaces().addAll(request.getInterfaces());
        storage.setSrcCalcInfo(expressionInfo);
        return storage;
    }

    protected BSLModuleIdInternal createBslModuleId(BslModuleInformation information, BslModuleReference reference) {
        BSLModuleIdInternal bslModuleId = DataFactory.eINSTANCE.createBSLModuleIdInternal();
        bslModuleId.setObjectID(reference.getParentUuid().toString());
        bslModuleId.setPropertyID(reference.getPropertyUuid().toString());
        if (information.isExternal()) {
            bslModuleId.setType(BSLModuleType.EXT_MD_MODULE);
            bslModuleId.setURL(information.getUrl());
        }

        return bslModuleId;
    }

    protected CalculationSourceDataStorage createCalculationSourceDataStorage(int stackLevel, int maxSize, boolean isMultiLine) {
        CalculationSourceDataStorage storage = CalculationsFactory.eINSTANCE.createCalculationSourceDataStorage();
        storage.setStackLevel(new BigDecimal(stackLevel));
        storage.setMaxTextSize(new BigDecimal(maxSize));
        return storage;
    }

    protected <T> EObject buildNewValue(T newValue) {
        SimpleAnyType value = XMLTypeFactory.eINSTANCE.createSimpleAnyType();
        if (newValue.getClass() == BigDecimal.class) {
            value.setInstanceType(XMLTypePackage.eINSTANCE.getDecimal());
            value.setValue(newValue);
            return value;
        } else if (newValue.getClass() == String.class) {
            value.setInstanceType(XMLTypePackage.eINSTANCE.getString());
            value.setValue(newValue);
            return value;
        } else if (newValue.getClass() == Boolean.class) {
            value.setInstanceType(XMLTypePackage.eINSTANCE.getBoolean());
            value.setValue(newValue);
            return value;
        } else if (newValue.getClass() == Date.class) {
            value.setInstanceType(XMLTypePackage.eINSTANCE.getDateTime());
            value.setValue(newValue);
            return value;
        } else {
            throw new IllegalArgumentException(String.format("Unsupported type: %s", newValue.getClass()));
        }
    }
}

