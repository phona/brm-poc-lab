package main

// PoC port of ttpos-server-go::notification_helper.GetStaffsByAccessPath.
// Same semantics: accessPath -> access.uuid -> role_access.role_uuid -> staff_role.staff_uuid,
// UNION with all is_super=1 staffs as a fallback for super admins.
// All queries respect delete_time = 0.
//
// Multi-tenancy: ttpos uses per-company databases named shop{companyUuid}. We open one
// gorm.DB per companyUuid on demand and cache it.

import (
	"errors"
	"fmt"
	"sync"

	"gorm.io/driver/mysql"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type ttposDBManager struct {
	dsnFmt string // e.g. "root:pwd@tcp(mysql:3306)/shop%d?charset=utf8mb4&parseTime=True&loc=Local"
	mu     sync.Mutex
	cache  map[uint64]*gorm.DB
}

func newTtposDBManager(dsnFmt string) *ttposDBManager {
	return &ttposDBManager{dsnFmt: dsnFmt, cache: make(map[uint64]*gorm.DB)}
}

func (m *ttposDBManager) getDB(companyUuid uint64) (*gorm.DB, error) {
	if companyUuid == 0 {
		return nil, errors.New("companyUuid is zero")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if db, ok := m.cache[companyUuid]; ok {
		return db, nil
	}
	dsn := fmt.Sprintf(m.dsnFmt, companyUuid)
	db, err := gorm.Open(mysql.Open(dsn), &gorm.Config{Logger: logger.Default.LogMode(logger.Silent)})
	if err != nil {
		return nil, fmt.Errorf("open shop%d db: %w", companyUuid, err)
	}
	m.cache[companyUuid] = db
	return db, nil
}

type ttposAccessRow struct{ Uuid uint64 }
type ttposStaffRow struct{ Uuid uint64 }

// GetStaffsByAccessPath: deduped list of staff uuids in companyUuid that hold accessPath,
// OR are super admins. Returns empty list (no error) when accessPath exists but no one holds it.
// Returns super-admins-only when accessPath does NOT exist (logs warning in real ttpos; here
// we just return the fallback list).
func (s *Server) ttposStaffsByAccessPath(companyUuid uint64, accessPath string) ([]uint64, error) {
	if accessPath == "" {
		return nil, errors.New("accessPath required")
	}
	db, err := s.ttposDB.getDB(companyUuid)
	if err != nil {
		return nil, err
	}

	// Step 1: access path -> access.uuid
	var access ttposAccessRow
	err = db.Table("ttpos_access").
		Select("uuid").
		Where("path = ? AND delete_time = 0", accessPath).
		Take(&access).Error
	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			// Mirror ttpos behavior: fall back to super admins only.
			return s.ttposSuperAdmins(db)
		}
		return nil, fmt.Errorf("query access failed: %w", err)
	}

	seen := make(map[uint64]struct{}, 16)

	// Step 2: role_uuids that hold this access
	var roleUuids []uint64
	if err := db.Table("ttpos_role_access").
		Where("access_uuid = ? AND delete_time = 0", access.Uuid).
		Pluck("role_uuid", &roleUuids).Error; err != nil {
		return nil, fmt.Errorf("query role_access failed: %w", err)
	}

	if len(roleUuids) > 0 {
		var staffByRole []uint64
		if err := db.Table("ttpos_staff_role").
			Where("role_uuid IN ? AND delete_time = 0", roleUuids).
			Pluck("staff_uuid", &staffByRole).Error; err != nil {
			return nil, fmt.Errorf("query staff_role failed: %w", err)
		}
		for _, sid := range staffByRole {
			seen[sid] = struct{}{}
		}
	}

	// Step 3: union with super admins
	supers, err := s.ttposSuperAdmins(db)
	if err != nil {
		return nil, err
	}
	for _, sid := range supers {
		seen[sid] = struct{}{}
	}

	out := make([]uint64, 0, len(seen))
	for sid := range seen {
		out = append(out, sid)
	}
	return out, nil
}

func (s *Server) ttposSuperAdmins(db *gorm.DB) ([]uint64, error) {
	var uuids []uint64
	if err := db.Table("ttpos_staff").
		Where("is_super = 1 AND is_disable = 0 AND delete_time = 0").
		Pluck("uuid", &uuids).Error; err != nil {
		return nil, fmt.Errorf("query super admins failed: %w", err)
	}
	return uuids, nil
}
