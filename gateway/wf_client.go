package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type wfClient struct {
	base   string
	client *http.Client
}

func newWFClient(base string) *wfClient {
	return &wfClient{
		base:   strings.TrimRight(base, "/"),
		client: &http.Client{Timeout: 30 * time.Second},
	}
}

func (w *wfClient) postJSON(path string, body any, out any) error {
	b, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequest(http.MethodPost, w.base+path, bytes.NewReader(b))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := w.client.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	raw, err := io.ReadAll(res.Body)
	if err != nil {
		return err
	}
	if res.StatusCode >= 300 {
		return fmt.Errorf("warm-flow %s: %s", res.Status, string(raw))
	}
	if out == nil {
		return nil
	}
	return decodeBigIntSafe(raw, out)
}

func (w *wfClient) getJSON(path string, out any) error {
	req, err := http.NewRequest(http.MethodGet, w.base+path, nil)
	if err != nil {
		return err
	}
	res, err := w.client.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	raw, err := io.ReadAll(res.Body)
	if err != nil {
		return err
	}
	if res.StatusCode >= 300 {
		return fmt.Errorf("warm-flow %s: %s", res.Status, string(raw))
	}
	return decodeBigIntSafe(raw, out)
}

// decodeBigIntSafe preserves Java long IDs (snowflake ~2e18 > JS MAX_SAFE_INTEGER).
// Default json.Unmarshal into map[string]any uses float64, which silently truncates
// the last few digits of long IDs (observed instance_id 2055306609218969602 ->
// gateway reported 2055306609218969600). UseNumber keeps the raw digit string.
func decodeBigIntSafe(raw []byte, out any) error {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	return dec.Decode(out)
}
