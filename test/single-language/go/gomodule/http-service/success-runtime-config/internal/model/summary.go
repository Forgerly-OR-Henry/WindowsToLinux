package model

type Summary struct {
	Status string `json:"status"`
	Items  []int  `json:"items"`
	Total  int    `json:"total"`
}

func NewSummary(items []int) Summary {
	result := Summary{Status: "ok", Items: append([]int(nil), items...)}
	for _, item := range result.Items {
		result.Total += item
	}
	return result
}
