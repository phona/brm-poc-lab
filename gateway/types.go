package main

// Envelope is the standard JSON response for the BFF (matches apiResp historically).
type Envelope struct {
	Code    int    `json:"code" example:"0"`
	Message string `json:"message,omitempty"`
	Data    any    `json:"data,omitempty"`
}

type resolveReq struct {
	BusinessID string            `json:"businessId"`
	RoleCode   string            `json:"roleCode"`
	Variables  map[string]string `json:"variables"`
}

type resolveData struct {
	UserIDs []string `json:"userIds"`
}

type flowFinishedReq struct {
	BusinessID string `json:"businessId"`
	Status     string `json:"status"`
}

type submitStartReq struct {
	OrderNo    string            `json:"orderNo"`
	FlowCode   string            `json:"flowCode"`
	CreateBy   string            `json:"createBy"`
	Variables  map[string]string `json:"variables"`
	CcUserIDs  []string          `json:"ccUserIds"`
}

type ccRecordReq struct {
	BusinessID string   `json:"businessId"`
	NodeCode   string   `json:"nodeCode"`
	Timing     string   `json:"timing"`
	CcUserIDs  []string `json:"ccUserIds"`
}

type wfStartReq struct {
	BusinessID string         `json:"businessId"`
	FlowCode   string         `json:"flowCode"`
	Handler    string         `json:"handler"`
	Variable   map[string]any `json:"variable"`
}

type wfSkipReq struct {
	TaskID   int64  `json:"taskId"`
	Handler  string `json:"handler"`
	Message  string `json:"message"`
	SkipType string `json:"skipType,omitempty"`
}

type wfTerminateReq struct {
	TaskID   int64  `json:"taskId"`
	Handler  string `json:"handler"`
	Message  string `json:"message"`
}

type wfTodoItem struct {
	TaskID     int64  `json:"taskId"`
	BusinessID string `json:"businessId"`
	NodeName   string `json:"nodeName"`
	NodeCode   string `json:"nodeCode"`
}

type wfTodoResp struct {
	List []wfTodoItem `json:"list"`
}

type aggregatedTodoItem struct {
	TaskID      int64  `json:"taskId"`
	OrderNo     string `json:"orderNo"`
	OrderAmount int64  `json:"orderAmount"`
	FromShop    string `json:"fromShop"`
	ToShop      string `json:"toShop"`
	TaskName    string `json:"taskName"`
	NodeCode    string `json:"nodeCode"`
}

type wfTransferReq struct {
	TaskID      int64    `json:"taskId"`
	Handler     string   `json:"handler"`
	AddHandlers []string `json:"addHandlers"`
	Message     string   `json:"message"`
}

type wfRejectLastReq struct {
	TaskID  int64  `json:"taskId"`
	Handler string `json:"handler"`
	Message string `json:"message"`
}

type wfRejectReq struct {
	TaskID  int64  `json:"taskId"`
	Handler string `json:"handler"`
	NodeCode string `json:"nodeCode"`
	Message string `json:"message"`
}

type wfRevokeReq struct {
	InstanceID int64  `json:"instanceId"`
	Handler    string `json:"handler"`
	Message    string `json:"message"`
}

type wfPendingReq struct {
	TaskID  int64  `json:"taskId"`
	Handler string `json:"handler"`
	Message string `json:"message"`
}

type wfSignatureReq struct {
	TaskID      int64    `json:"taskId"`
	Handler     string   `json:"handler"`
	AddHandlers []string `json:"addHandlers"`
	Message     string   `json:"message"`
}

type wfReductionReq struct {
	TaskID           int64    `json:"taskId"`
	Handler          string   `json:"handler"`
	ReductionHandlers []string `json:"reductionHandlers"`
	Message          string   `json:"message"`
}
