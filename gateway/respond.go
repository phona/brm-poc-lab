package main

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

func okJSON(c *gin.Context, data any) {
	c.JSON(http.StatusOK, Envelope{Code: 0, Data: data})
}

func failJSON(c *gin.Context, code int, msg string) {
	c.JSON(http.StatusOK, Envelope{Code: code, Message: msg})
}
