package service
import (
    "fmt"
    "regexp"
    "strconv"
    "strings"
    "example.com/windowstolinux/fixture/internal/model"
)
var digits = regexp.MustCompile(`^[0-9]{1,10}$`)
func Summarize(raw *string) (model.Summary, error) {
    value := "1,2,3"
    if raw != nil { value = *raw }
    tokens := strings.Split(value, ",")
    if len(tokens) > 20 { return model.Summary{}, fmt.Errorf("invalid-values") }
    items := make([]int, 0, len(tokens))
    for _, token := range tokens {
        number, err := strconv.Atoi(token)
        if !digits.MatchString(token) || err != nil || number < 0 || number > 10000 {
            return model.Summary{}, fmt.Errorf("invalid-values")
        }
        items = append(items, number)
    }
    return model.NewSummary(items), nil
}
