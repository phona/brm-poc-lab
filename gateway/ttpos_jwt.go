package main

// ttpos JWT: mirrors the claims/signing scheme of ttpos-server-go (main/pkg/auth/jwt.go).
// We re-declare the Claims struct rather than importing the ttpos module to keep the
// PoC gateway self-contained. Field tags and signing algo (HS256) match exactly so a
// token generated here is accepted by ttpos and vice versa.

import (
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

type ttposAssistant struct {
	DeviceId  string `json:"device_id"`
	StaffUuid uint64 `json:"staff_uuid"`
}

// TtposClaims mirrors main/pkg/auth/jwt.go::Claims byte-for-byte (field names + json tags).
type TtposClaims struct {
	Source         string         `json:"source"`
	CompanyUuid    uint64         `json:"company_uuid"`
	StaffUuid      uint64         `json:"staff_uuid"`
	MemberUuid     uint64         `json:"member_uuid"`
	DeviceUuid     uint64         `json:"device_uuid"`
	DeviceId       string         `json:"device_id"`
	Assistant      ttposAssistant `json:"assistant"`
	IsRefreshToken bool           `json:"is_refresh_token"`
	Brand          string         `json:"brand"`
	jwt.RegisteredClaims
}

func ttposGenerateToken(claims TtposClaims, secret string, expireSeconds int) (string, error) {
	claims.RegisteredClaims = jwt.RegisteredClaims{
		ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Second * time.Duration(expireSeconds))),
		IssuedAt:  jwt.NewNumericDate(time.Now()),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(secret))
}

func ttposParseToken(tokenString, secret string) (*TtposClaims, error) {
	token, err := jwt.ParseWithClaims(tokenString, &TtposClaims{}, func(t *jwt.Token) (any, error) {
		if t.Method.Alg() != "HS256" {
			return nil, fmt.Errorf("invalid signing method: %v", t.Header["alg"])
		}
		return []byte(secret), nil
	})
	if err != nil {
		return nil, err
	}
	c, ok := token.Claims.(*TtposClaims)
	if !ok || !token.Valid {
		return nil, errors.New("invalid token")
	}
	return c, nil
}

// requireTtposJWT validates Bearer tokens, stores CompanyUuid/StaffUuid in gin context.
func (s *Server) requireTtposJWT(c *gin.Context) {
	auth := c.GetHeader("Authorization")
	if len(auth) < 8 || auth[:7] != "Bearer " {
		c.AbortWithStatusJSON(http.StatusUnauthorized, Envelope{Code: 401, Message: "missing or malformed Authorization header"})
		return
	}
	claims, err := ttposParseToken(auth[7:], s.jwtSecret)
	if err != nil {
		c.AbortWithStatusJSON(http.StatusUnauthorized, Envelope{Code: 401, Message: "invalid token: " + err.Error()})
		return
	}
	if claims.IsRefreshToken {
		c.AbortWithStatusJSON(http.StatusUnauthorized, Envelope{Code: 401, Message: "refresh token cannot access business endpoints"})
		return
	}
	if claims.CompanyUuid == 0 || claims.StaffUuid == 0 {
		c.AbortWithStatusJSON(http.StatusUnauthorized, Envelope{Code: 401, Message: "company_uuid / staff_uuid required"})
		return
	}
	c.Set("ttpos_company_uuid", claims.CompanyUuid)
	c.Set("ttpos_staff_uuid", claims.StaffUuid)
	c.Set("ttpos_source", claims.Source)
	c.Next()
}

func ctxCompanyUuid(c *gin.Context) uint64 {
	v, _ := c.Get("ttpos_company_uuid")
	if u, ok := v.(uint64); ok {
		return u
	}
	return 0
}

func ctxStaffUuid(c *gin.Context) uint64 {
	v, _ := c.Get("ttpos_staff_uuid")
	if u, ok := v.(uint64); ok {
		return u
	}
	return 0
}

func u64ToStr(u uint64) string { return strconv.FormatUint(u, 10) }
