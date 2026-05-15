package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"gorm.io/gorm"
)

// Server is the PoC BFF (biz DB + Warm-Flow HTTP client).
type Server struct {
	db        *gorm.DB
	wf        *wfClient
	apiKey    string
	jwtSecret string          // ttpos-compatible JWT secret (HS256)
	ttposDB   *ttposDBManager // per-companyUuid shop{n} db handles
}

func NewServer(db *gorm.DB, wf *wfClient, apiKey, jwtSecret string, ttposDB *ttposDBManager) *Server {
	return &Server{db: db, wf: wf, apiKey: apiKey, jwtSecret: jwtSecret, ttposDB: ttposDB}
}

func (s *Server) Register(r *gin.Engine) {
	r.GET("/health", s.Health)

	// ttpos-integrated business surface: real JWT, real RBAC.
	// Separate from the legacy /internal/poc/warm-flow surface so the PoC mock path keeps working.
	ttpos := r.Group("/api/v1/ttpos")
	ttpos.Use(s.requireTtposJWT)
	ttpos.POST("/flow/start", s.PostTtposFlowStart)
	ttpos.GET("/flow/todo", s.GetTtposFlowTodo)
	ttpos.POST("/flow/approve", s.PostTtposFlowApprove)

	// Dev helper: sign a token with our shared secret. Disabled in prod.
	r.POST("/dev/gen-token", s.PostDevGenToken)

	internal := r.Group("/internal/poc/warm-flow")
	internal.Use(s.requireAPIKey)
	internal.POST("/resolve-approver", s.PostResolveApprover)
	internal.POST("/cc-record", s.PostCcRecord)
	internal.GET("/cc-record", s.GetCcRecord)
	internal.POST("/flow-finished", s.PostFlowFinished)
	internal.GET("/tasks/todo", s.GetTasksTodo)
	internal.POST("/transfer/submit-start", s.PostTransferSubmitStart)
	internal.POST("/task/skip", s.PostTaskSkip)
	internal.POST("/task/terminate", s.PostTaskTerminate)
	internal.POST("/task/cancel", s.PostTaskCancel)
	// 新增：转办/委派/加签/减签/退回/查询
	// 注：reject-last/revoke/pending 需 Warm-Flow 1.3.4+，1.3.3 暂不支持
	internal.POST("/task/transfer", s.PostTaskTransfer)
	internal.POST("/task/depute", s.PostTaskDepute)
	internal.POST("/task/add-signature", s.PostTaskAddSignature)
	internal.POST("/task/reduction-signature", s.PostTaskReductionSignature)
	internal.POST("/task/reject", s.PostTaskReject)
	internal.GET("/task/detail", s.GetTaskDetail)
	internal.GET("/instance/tasks", s.GetInstanceTasks)
}

func (s *Server) requireAPIKey(c *gin.Context) {
	if c.GetHeader("X-API-KEY") != s.apiKey {
		c.AbortWithStatusJSON(http.StatusUnauthorized, Envelope{Code: 401, Message: "Unauthorized"})
		return
	}
	c.Next()
}

// Health Liveness probe.
// @Summary Liveness
// @Description Returns plain text ok
// @Tags public
// @Produce plain
// @Success 200 {string} string "ok"
// @Router /health [get]
func (s *Server) Health(c *gin.Context) {
	c.String(http.StatusOK, "ok")
}

// PostResolveApprover resolves mock approvers (Java webhooks call this).
// @Summary Resolve approvers by business + role
// @Description Warm-Flow calls this webhook to resolve handler user IDs for a node. PoC: looks up mock transfer order by businessId (order no), then for roleCode RECEIVER_MANAGER returns the receiving shop's region_manager_user_id from poc_mock_shop.
// @Tags internal
// @Accept json
// @Produce json
// @Param X-API-KEY header string true "API key" default(dev-poc-key)
// @Param req body resolveReq true "Payload"
// @Success 200 {object} Envelope{data=resolveData}
// @Failure 401 {object} Envelope
// @Router /internal/poc/warm-flow/resolve-approver [post]
// @Security ApiKeyAuth
func (s *Server) PostResolveApprover(c *gin.Context) {
	var req resolveReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}

	// ttpos integration path: roleCode "ACCESS:<access_path>" resolves via real ttpos RBAC.
	// companyUuid is read from FlowParams.variable["companyUuid"] (set by the gateway when starting).
	if strings.HasPrefix(req.RoleCode, "ACCESS:") {
		accessPath := strings.TrimSpace(strings.TrimPrefix(req.RoleCode, "ACCESS:"))
		companyUuidStr := req.Variables["companyUuid"]
		companyUuid, _ := strconv.ParseUint(companyUuidStr, 10, 64)
		if companyUuid == 0 {
			failJSON(c, -1, "companyUuid missing from flow variables")
			return
		}
		ids, err := s.ttposStaffsByAccessPath(companyUuid, accessPath)
		if err != nil {
			failJSON(c, -1, "ttpos resolve failed: "+err.Error())
			return
		}
		strs := make([]string, 0, len(ids))
		for _, u := range ids {
			strs = append(strs, strconv.FormatUint(u, 10))
		}
		okJSON(c, resolveData{UserIDs: strs})
		return
	}

	var order MockTransferOrder
	if err := s.db.Where("order_no = ?", req.BusinessID).First(&order).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			failJSON(c, -1, "order not found")
			return
		}
		failJSON(c, -1, err.Error())
		return
	}
	switch strings.ToUpper(req.RoleCode) {
	case "RECEIVER_MANAGER":
		var shop MockShop
		if err := s.db.Where("shop_key = ?", order.ToShop).First(&shop).Error; err != nil {
			failJSON(c, -1, "shop not found for to_shop")
			return
		}
		if shop.RegionManagerUserID == "" {
			failJSON(c, -1, "no region manager configured")
			return
		}
		okJSON(c, resolveData{UserIDs: []string{shop.RegionManagerUserID}})
	default:
		failJSON(c, -1, "unknown roleCode: "+req.RoleCode)
	}
}

// PostCcRecord appends a CC audit row (Java calls this).
// @Summary Record CC event
// @Description Persists one CC (carbon-copy) audit row when the engine fires a CC / notify step. Body: businessId, nodeCode, timing, ccUserIds array. Stored in poc_cc_event as JSON text for ccUserIds.
// @Tags internal
// @Accept json
// @Produce json
// @Param X-API-KEY header string true "API key"
// @Param req body ccRecordReq true "Payload"
// @Success 200 {object} Envelope
// @Failure 401 {object} Envelope
// @Router /internal/poc/warm-flow/cc-record [post]
// @Security ApiKeyAuth
func (s *Server) PostCcRecord(c *gin.Context) {
	var req ccRecordReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	if req.BusinessID == "" {
		failJSON(c, -1, "businessId required")
		return
	}
	b, _ := json.Marshal(req.CcUserIDs)
	row := PocCcEvent{
		BusinessID: req.BusinessID,
		NodeCode:   req.NodeCode,
		Timing:     req.Timing,
		CcUserIDs:  string(b),
	}
	if err := s.db.Create(&row).Error; err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, map[string]any{"id": row.ID})
}

// GetCcRecord lists recent CC rows.
// @Summary List CC records
// @Description Returns the latest 50 CC rows, newest first. Optional query businessId filters to one order. For debugging and PoC verification.
// @Tags internal
// @Produce json
// @Param X-API-KEY header string true "API key"
// @Param businessId query string false "Filter by business id"
// @Success 200 {object} Envelope{data=[]PocCcEvent}
// @Failure 401 {object} Envelope
// @Router /internal/poc/warm-flow/cc-record [get]
// @Security ApiKeyAuth
func (s *Server) GetCcRecord(c *gin.Context) {
	biz := c.Query("businessId")
	q := s.db.Model(&PocCcEvent{}).Order("id DESC").Limit(50)
	if biz != "" {
		q = q.Where("business_id = ?", biz)
	}
	var rows []PocCcEvent
	if err := q.Find(&rows).Error; err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, rows)
}

// PostFlowFinished updates mock order status when flow ends (Java calls this).
// @Summary Flow finished callback
// @Description Java TaskListener invokes this when the flow instance ends. Sets poc_mock_transfer_order.status for the given businessId (order no). Body status defaults to approved if empty (e.g. completed path).
// @Tags internal
// @Accept json
// @Produce json
// @Param X-API-KEY header string true "API key"
// @Param req body flowFinishedReq true "Payload"
// @Success 200 {object} Envelope
// @Failure 401 {object} Envelope
// @Router /internal/poc/warm-flow/flow-finished [post]
// @Security ApiKeyAuth
func (s *Server) PostFlowFinished(c *gin.Context) {
	var req flowFinishedReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	st := strings.ToLower(strings.TrimSpace(req.Status))
	if st == "" {
		st = "approved"
	}
	res := s.db.Model(&MockTransferOrder{}).Where("order_no = ?", req.BusinessID).Update("status", st)
	if res.Error != nil {
		failJSON(c, -1, res.Error.Error())
		return
	}
	okJSON(c, map[string]any{"updated": res.RowsAffected})
}

// GetTasksTodo aggregates Warm-Flow todos with mock order fields.
// @Summary Aggregated todo list
// @Description Calls Warm-Flow GET /api/task/todo?handler=userId, then enriches each task with amount, fromShop, and toShop from poc_mock_transfer_order. Use this BFF endpoint for an approval todo list in client apps instead of calling Warm-Flow directly.
// @Tags internal
// @Produce json
// @Param X-API-KEY header string true "API key"
// @Param userId query string true "Handler user id"
// @Success 200 {object} Envelope{data=[]aggregatedTodoItem}
// @Failure 401 {object} Envelope
// @Router /internal/poc/warm-flow/tasks/todo [get]
// @Security ApiKeyAuth
func (s *Server) GetTasksTodo(c *gin.Context) {
	userID := c.Query("userId")
	if userID == "" {
		failJSON(c, -1, "userId required")
		return
	}
	var wfOut wfTodoResp
	if err := s.wf.getJSON("/api/task/todo?handler="+userID, &wfOut); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	orderNos := make([]string, 0, len(wfOut.List))
	for _, t := range wfOut.List {
		if t.BusinessID != "" {
			orderNos = append(orderNos, t.BusinessID)
		}
	}
	var orders []MockTransferOrder
	if len(orderNos) > 0 {
		s.db.Where("order_no IN ?", orderNos).Find(&orders)
	}
	byNo := map[string]MockTransferOrder{}
	for _, o := range orders {
		byNo[o.OrderNo] = o
	}
	out := make([]aggregatedTodoItem, 0, len(wfOut.List))
	for _, t := range wfOut.List {
		o := byNo[t.BusinessID]
		out = append(out, aggregatedTodoItem{
			TaskID:      t.TaskID,
			OrderNo:     t.BusinessID,
			OrderAmount: o.Amount,
			FromShop:    o.FromShop,
			ToShop:      o.ToShop,
			TaskName:    t.NodeName,
			NodeCode:    t.NodeCode,
		})
	}
	okJSON(c, out)
}

// PostTransferSubmitStart starts Warm-Flow and sets order approving.
// @Summary Start transfer flow
// @Description Sets mock order status to approving, then POSTs /api/flow/start to Warm-Flow with businessId=orderNo, flowCode, handler=createBy, and variables (including optional ccUserIds as array). Returns engine JSON inside envelope data on success. Typical first call from app when submitting a transfer for approval.
// @Tags internal
// @Accept json
// @Produce json
// @Param X-API-KEY header string true "API key"
// @Param req body submitStartReq true "Payload"
// @Success 200 {object} Envelope{data=map[string]interface{}}
// @Failure 401 {object} Envelope
// @Router /internal/poc/warm-flow/transfer/submit-start [post]
// @Security ApiKeyAuth
func (s *Server) PostTransferSubmitStart(c *gin.Context) {
	var req submitStartReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	if req.OrderNo == "" || req.FlowCode == "" || req.CreateBy == "" {
		failJSON(c, -1, "orderNo, flowCode, createBy required")
		return
	}
	res := s.db.Model(&MockTransferOrder{}).Where("order_no = ?", req.OrderNo).Update("status", "approving")
	if res.Error != nil {
		failJSON(c, -1, res.Error.Error())
		return
	}
	vars := map[string]any{}
	for k, v := range req.Variables {
		vars[k] = v
	}
	if len(req.CcUserIDs) > 0 {
		arr := make([]any, len(req.CcUserIDs))
		for i, str := range req.CcUserIDs {
			arr[i] = str
		}
		vars["ccUserIds"] = arr
	}
	// Warm-Flow 1.3.8: pre-resolve dynamic approver since 'permission' listener was removed
	var order MockTransferOrder
	if err := s.db.Where("order_no = ?", req.OrderNo).First(&order).Error; err == nil {
		var shop MockShop
		if err := s.db.Where("shop_key = ?", order.ToShop).First(&shop).Error; err == nil && shop.RegionManagerUserID != "" {
			vars["recvApprover"] = shop.RegionManagerUserID
		}
	}
	// Persist CC records upfront (previously done via WebhookPermissionListener)
	if len(req.CcUserIDs) > 0 {
		b, _ := json.Marshal(req.CcUserIDs)
		s.db.Create(&PocCcEvent{
			BusinessID: req.OrderNo,
			NodeCode:   "recv_mgr",
			Timing:     "before_recv_assigned",
			CcUserIDs:  string(b),
		})
	}
	body := wfStartReq{
		BusinessID: req.OrderNo,
		FlowCode:   req.FlowCode,
		Handler:    req.CreateBy,
		Variable:   vars,
	}
	var raw map[string]any
	if err := s.wf.postJSON("/api/flow/start", body, &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, raw)
}

// PostTaskSkip proxies skip to Warm-Flow (PASS by default; optional skipType).
// @Summary Skip / approve task
// @Description Proxies to Warm-Flow POST /api/task/skip. Use to approve/pass the current human task (taskId, handler, message; skipType optional). Do not use for reject—use /task/terminate instead for PoC reject path.
// @Tags internal
// @Accept json
// @Produce json
// @Param X-API-KEY header string true "API key"
// @Param req body wfSkipReq true "Payload"
// @Success 200 {object} Envelope{data=map[string]interface{}}
// @Failure 401 {object} Envelope
// @Router /internal/poc/warm-flow/task/skip [post]
// @Security ApiKeyAuth
func (s *Server) PostTaskSkip(c *gin.Context) {
	var req wfSkipReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	var raw map[string]any
	if err := s.wf.postJSON("/api/task/skip", req, &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, raw)
}

// PostTaskTerminate rejects and ends instance (Warm-Flow termination).
// @Summary Terminate task (reject)
// @Description Proxies to Warm-Flow POST /api/task/terminate. Ends the instance with reject-style termination (PoC TB-1003). Updates downstream status via flow-finished when engine completes.
// @Tags internal
// @Accept json
// @Produce json
// @Param X-API-KEY header string true "API key"
// @Param req body wfTerminateReq true "Payload"
// @Success 200 {object} Envelope{data=map[string]interface{}}
// @Failure 401 {object} Envelope
// @Router /internal/poc/warm-flow/task/terminate [post]
// @Security ApiKeyAuth
func (s *Server) PostTaskTerminate(c *gin.Context) {
	var req wfTerminateReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	var raw map[string]any
	if err := s.wf.postJSON("/api/task/terminate", req, &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, raw)
}

// PostTaskCancel cancels and ends instance.
// @Summary Cancel task / flow
// @Description Proxies to Warm-Flow POST /api/task/cancel. Cancels the instance (PoC TB-1004). Same body shape as terminate: taskId, handler, message.
// @Tags internal
// @Accept json
// @Produce json
// @Param X-API-KEY header string true "API key"
// @Param req body wfTerminateReq true "Payload"
// @Success 200 {object} Envelope{data=map[string]interface{}}
// @Failure 401 {object} Envelope
// @Router /internal/poc/warm-flow/task/cancel [post]
// @Security ApiKeyAuth
func (s *Server) PostTaskCancel(c *gin.Context) {
	var req wfTerminateReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	var raw map[string]any
	if err := s.wf.postJSON("/api/task/cancel", req, &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, raw)
}

// PostTaskTransfer proxies transfer to Warm-Flow.
func (s *Server) PostTaskTransfer(c *gin.Context) {
	var req wfTransferReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	var raw map[string]any
	if err := s.wf.postJSON("/api/task/transfer", req, &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, raw)
}

// PostTaskDepute proxies depute to Warm-Flow.
func (s *Server) PostTaskDepute(c *gin.Context) {
	var req wfTransferReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	var raw map[string]any
	if err := s.wf.postJSON("/api/task/depute", req, &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, raw)
}

// PostTaskAddSignature proxies add-signature to Warm-Flow.
func (s *Server) PostTaskAddSignature(c *gin.Context) {
	var req wfSignatureReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	var raw map[string]any
	if err := s.wf.postJSON("/api/task/add-signature", req, &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, raw)
}

// PostTaskReductionSignature proxies reduction-signature to Warm-Flow.
func (s *Server) PostTaskReductionSignature(c *gin.Context) {
	var req wfReductionReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	var raw map[string]any
	if err := s.wf.postJSON("/api/task/reduction-signature", req, &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, raw)
}

// PostTaskRejectLast proxies reject-last to Warm-Flow.
func (s *Server) PostTaskRejectLast(c *gin.Context) {
	var req wfRejectLastReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	var raw map[string]any
	if err := s.wf.postJSON("/api/task/reject-last", req, &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, raw)
}

// PostTaskReject proxies reject (to specified node) to Warm-Flow.
func (s *Server) PostTaskReject(c *gin.Context) {
	var req wfRejectReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	var raw map[string]any
	if err := s.wf.postJSON("/api/task/reject", req, &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, raw)
}

// PostFlowRevoke proxies revoke to Warm-Flow.
func (s *Server) PostFlowRevoke(c *gin.Context) {
	var req wfRevokeReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	var raw map[string]any
	if err := s.wf.postJSON("/api/flow/revoke", req, &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, raw)
}

// PostTaskPending proxies pending to Warm-Flow.
func (s *Server) PostTaskPending(c *gin.Context) {
	var req wfPendingReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	var raw map[string]any
	if err := s.wf.postJSON("/api/task/pending", req, &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, raw)
}

// GetTaskDetail queries task detail with handlers from Warm-Flow.
func (s *Server) GetTaskDetail(c *gin.Context) {
	taskID := c.Query("taskId")
	if taskID == "" {
		failJSON(c, -1, "taskId required")
		return
	}
	var raw map[string]any
	if err := s.wf.getJSON("/api/task/detail?taskId="+taskID, &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, raw)
}

// GetInstanceTasks lists tasks by instance from Warm-Flow.
func (s *Server) GetInstanceTasks(c *gin.Context) {
	insID := c.Query("instanceId")
	if insID == "" {
		failJSON(c, -1, "instanceId required")
		return
	}
	var raw map[string]any
	if err := s.wf.getJSON("/api/instance/tasks?instanceId="+insID, &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, raw)
}

// ========== ttpos-integrated business surface (JWT-protected) ==========

type ttposStartReq struct {
	BusinessID string         `json:"businessId"`
	FlowCode   string         `json:"flowCode"`
	Variable   map[string]any `json:"variable"`
}

// PostTtposFlowStart: handler = StaffUuid from JWT, companyUuid injected into variables
// so the resolve-approver webhook can scope its RBAC lookup to the right tenant DB.
func (s *Server) PostTtposFlowStart(c *gin.Context) {
	var req ttposStartReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	if req.BusinessID == "" || req.FlowCode == "" {
		failJSON(c, -1, "businessId, flowCode required")
		return
	}
	companyUuid := ctxCompanyUuid(c)
	staffUuid := ctxStaffUuid(c)

	vars := map[string]any{}
	for k, v := range req.Variable {
		vars[k] = v
	}
	// Critical: stamp companyUuid into flow variables so dynamic-approver webhooks
	// know which tenant DB to query when resolving ACCESS:<path>.
	vars["companyUuid"] = u64ToStr(companyUuid)
	vars["createBy"] = u64ToStr(staffUuid)

	body := wfStartReq{
		BusinessID: req.BusinessID,
		FlowCode:   req.FlowCode,
		Handler:    u64ToStr(staffUuid),
		Variable:   vars,
	}
	var raw map[string]any
	if err := s.wf.postJSON("/api/flow/start", body, &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, gin.H{
		"engineResponse": raw,
		"companyUuid":    companyUuid,
		"staffUuid":      staffUuid,
	})
}

// GetTtposFlowTodo lists Warm-Flow tasks assigned to the JWT staff. The handler the
// engine matches against is the StaffUuid string; no body-side filtering required.
func (s *Server) GetTtposFlowTodo(c *gin.Context) {
	staffUuid := ctxStaffUuid(c)
	var raw map[string]any
	if err := s.wf.getJSON("/api/task/todo?handler="+u64ToStr(staffUuid), &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, raw)
}

type ttposApproveReq struct {
	TaskID  int64  `json:"taskId"`
	Message string `json:"message"`
}

// PostTtposFlowApprove proxies PASS to engine using the caller's StaffUuid as handler.
// Engine will reject if StaffUuid is not in the task's permissionList — proving the
// RBAC -> permissionFlag binding is honored end-to-end.
func (s *Server) PostTtposFlowApprove(c *gin.Context) {
	var req ttposApproveReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	if req.TaskID == 0 {
		failJSON(c, -1, "taskId required")
		return
	}
	staffUuid := ctxStaffUuid(c)
	body := wfSkipReq{
		TaskID:  req.TaskID,
		Handler: u64ToStr(staffUuid),
		Message: req.Message,
	}
	var raw map[string]any
	if err := s.wf.postJSON("/api/task/skip", body, &raw); err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, raw)
}

// ========== Dev token generator ==========

type devGenTokenReq struct {
	CompanyUuid uint64 `json:"companyUuid"`
	StaffUuid   uint64 `json:"staffUuid"`
	Source      string `json:"source"`
	ExpireSec   int    `json:"expireSec"`
}

// PostDevGenToken signs a ttpos-shape JWT for PoC verification. Never deploy.
func (s *Server) PostDevGenToken(c *gin.Context) {
	var req devGenTokenReq
	if err := c.ShouldBindJSON(&req); err != nil {
		failJSON(c, -1, "invalid json")
		return
	}
	if req.CompanyUuid == 0 || req.StaffUuid == 0 {
		failJSON(c, -1, "companyUuid, staffUuid required")
		return
	}
	if req.Source == "" {
		req.Source = "shop"
	}
	if req.ExpireSec == 0 {
		req.ExpireSec = 3600
	}
	tok, err := ttposGenerateToken(TtposClaims{
		Source:      req.Source,
		CompanyUuid: req.CompanyUuid,
		StaffUuid:   req.StaffUuid,
	}, s.jwtSecret, req.ExpireSec)
	if err != nil {
		failJSON(c, -1, err.Error())
		return
	}
	okJSON(c, gin.H{"token": tok})
}
