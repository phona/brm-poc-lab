package main

type MockShop struct {
	ID                  int64  `gorm:"column:id;primaryKey"`
	ShopKey             string `gorm:"column:shop_key"`
	DisplayName         string `gorm:"column:display_name"`
	ManagerUserID       string `gorm:"column:manager_user_id"`
	RegionManagerUserID string `gorm:"column:region_manager_user_id"`
}

func (MockShop) TableName() string { return "poc_mock_shop" }

type MockTransferOrder struct {
	ID       int64  `gorm:"column:id;primaryKey"`
	OrderNo  string `gorm:"column:order_no"`
	FromShop string `gorm:"column:from_shop"`
	ToShop   string `gorm:"column:to_shop"`
	Amount   int64  `gorm:"column:amount"`
	Status   string `gorm:"column:status"`
}

func (MockTransferOrder) TableName() string { return "poc_mock_transfer_order" }

type PocCcEvent struct {
	ID         int64  `gorm:"column:id;primaryKey;autoIncrement" json:"id"`
	BusinessID string `gorm:"column:business_id" json:"businessId"`
	NodeCode   string `gorm:"column:node_code" json:"nodeCode"`
	Timing     string `gorm:"column:timing" json:"timing"`
	CcUserIDs  string `gorm:"column:cc_user_ids;type:text" json:"ccUserIds"`
}

func (PocCcEvent) TableName() string { return "poc_cc_event" }
