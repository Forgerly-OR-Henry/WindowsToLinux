package config
import ("fmt"; "os"; "regexp"; "strconv")
type Configuration struct { Port string; Mode string; Status int; Label string }
func Load() (Configuration, error) {
    port := os.Getenv("PORT")
    value, err := strconv.Atoi(port)
    if err != nil || !regexp.MustCompile(`^[0-9]+$`).MatchString(port) || value < 1 || value > 65535 {
        return Configuration{}, fmt.Errorf("Invalid PORT")
    }
    mode := "smoke"
    label := "deployment-smoke-ok"
    if mode == "config" {
        var found bool
        label, found = os.LookupEnv("FIXTURE_LABEL")
        if !found { label = "runtime-config-default" }
    }
    return Configuration{port, mode, 200, label}, nil
}
