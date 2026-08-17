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

func sendTelegram(token, chat, text string) error {
	token = strings.TrimSpace(token)
	chat = strings.TrimSpace(chat)
	if token == "" || chat == "" {
		return fmt.Errorf("telegram не настроен")
	}
	body, err := json.Marshal(map[string]any{
		"chat_id":                  chat,
		"text":                     text,
		"disable_web_page_preview": true,
	})
	if err != nil {
		return err
	}
	url := "https://api.telegram.org/bot" + token + "/sendMessage"
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	client := &http.Client{Timeout: 20 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode >= 300 {
		return fmt.Errorf("telegram HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	var out struct {
		OK          bool   `json:"ok"`
		Description string `json:"description"`
	}
	_ = json.Unmarshal(raw, &out)
	if !out.OK {
		if out.Description == "" {
			out.Description = strings.TrimSpace(string(raw))
		}
		return fmt.Errorf("telegram: %s", out.Description)
	}
	return nil
}
