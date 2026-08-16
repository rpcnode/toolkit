package main

import "fmt"

func truthy(v any) bool {
	switch t := v.(type) {
	case bool:
		return t
	case string:
		return t == "true" || t == "1" || t == "active"
	default:
		return false
	}
}

func str(v any, def string) string {
	if v == nil {
		return def
	}
	s := fmt.Sprint(v)
	if s == "" || s == "<nil>" {
		return def
	}
	return s
}
