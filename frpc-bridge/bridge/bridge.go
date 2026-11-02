package bridge

import (
	"context"
	"errors"
	"fmt"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/fatedier/frp/client"
	"github.com/fatedier/frp/client/proxy"
	"github.com/fatedier/frp/pkg/config"
	"github.com/fatedier/frp/pkg/config/source"
	v1 "github.com/fatedier/frp/pkg/config/v1"
	"github.com/fatedier/frp/pkg/config/v1/validation"
	"github.com/fatedier/frp/pkg/policy/security"
	"github.com/fatedier/frp/pkg/util/log"
)

type runState struct {
	service   *client.Service
	cancel    context.CancelFunc
	done      chan error
	proxies   []string
	connected bool
	startedAt time.Time
}

var state struct {
	sync.Mutex
	run       *runState
	status    string
	lastError string
}

// Validate parses and validates a complete frpc configuration without opening a connection.
func Validate(configPath string) error {
	_, _, _, err := loadConfig(configPath)
	return safeError(err, configPath)
}

// Start validates configPath and starts one embedded frpc client instance.
func Start(configPath string) error {
	state.Lock()
	defer state.Unlock()
	if state.run != nil {
		return errors.New("frpc is already running")
	}
	common, aggregator, proxyNames, err := loadConfig(configPath)
	if err != nil {
		state.status = "error"
		state.lastError = safeError(err, configPath).Error()
		return errors.New(state.lastError)
	}
	common.LoginFailExit = new(false) // Retry transient network and server failures until Stop.
	log.InitLogger(common.Log.To, "error", int(common.Log.MaxDays), common.Log.DisablePrintColor)
	svr, err := client.NewService(client.ServiceOptions{Common: common, ConfigSourceAggregator: aggregator, UnsafeFeatures: security.NewUnsafeFeatures([]string{}), ConfigFilePath: configPath})
	if err != nil {
		state.status = "error"
		state.lastError = safeError(err, configPath).Error()
		return errors.New(state.lastError)
	}
	ctx, cancel := context.WithCancel(context.Background())
	r := &runState{service: svr, cancel: cancel, done: make(chan error, 1), proxies: proxyNames, startedAt: time.Now()}
	state.run = r
	state.status = "starting"
	state.lastError = ""
	go func() {
		runErr := svr.Run(ctx)
		r.done <- runErr
		state.Lock()
		defer state.Unlock()
		if state.run != r {
			return
		}
		state.run = nil
		if runErr != nil && !errors.Is(runErr, context.Canceled) {
			state.status = "error"
			state.lastError = safeError(runErr, configPath).Error()
		} else {
			state.status = "stopped"
		}
	}()
	return nil
}

// Stop closes the active embedded frpc client and waits briefly for its run loop to exit.
func Stop() error {
	state.Lock()
	r := state.run
	if r == nil {
		state.status = "stopped"
		state.Unlock()
		return nil
	}
	state.run = nil
	state.status = "stopped"
	state.lastError = ""
	state.Unlock()
	r.cancel()
	r.service.Close()
	select {
	case <-r.done:
	case <-time.After(3 * time.Second):
		return errors.New("frpc stop timed out")
	}
	return nil
}

// Status returns stopped, starting, connected, reconnecting, or error.
func Status() string {
	state.Lock()
	defer state.Unlock()
	r := state.run
	if r == nil {
		if state.status == "" {
			return "stopped"
		}
		return state.status
	}
	for _, name := range r.proxies {
		if p, ok := r.service.StatusExporter().GetProxyStatus(name); ok && p.Phase == proxy.ProxyPhaseRunning {
			r.connected = true
			state.status = "connected"
			return "connected"
		}
	}
	if r.connected || time.Since(r.startedAt) >= 10*time.Second {
		state.status = "reconnecting"
		return "reconnecting"
	}
	state.status = "starting"
	return "starting"
}

// LastError returns a short error string with configured secrets removed.
func LastError() string {
	state.Lock()
	defer state.Unlock()
	return state.lastError
}

func loadConfig(path string) (common *v1.ClientCommonConfig, aggregator *source.Aggregator, proxyNames []string, err error) {
	if strings.TrimSpace(path) == "" {
		return nil, nil, nil, errors.New("configuration path is empty")
	}
	result, err := config.LoadClientConfigResult(path, true)
	if err != nil {
		return nil, nil, nil, err
	}
	if strings.TrimSpace(result.Common.ServerAddr) == "" || result.Common.ServerAddr == "0.0.0.0" {
		return nil, nil, nil, errors.New("serverAddr must be set")
	}
	cs := source.NewConfigSource()
	if err = cs.ReplaceAll(result.Proxies, result.Visitors); err != nil {
		return nil, nil, nil, err
	}
	aggregator = source.NewAggregator(cs)
	proxies, visitors, err := aggregator.Load()
	if err != nil {
		return nil, nil, nil, err
	}
	proxies, visitors = config.FilterClientConfigurers(result.Common, proxies, visitors)
	proxies = config.CompleteProxyConfigurers(proxies)
	visitors = config.CompleteVisitorConfigurers(visitors)
	if len(proxies) == 0 {
		return nil, nil, nil, errors.New("configuration has no enabled proxy")
	}
	_, err = validation.ValidateAllClientConfig(result.Common, proxies, visitors, security.NewUnsafeFeatures([]string{}))
	if err != nil {
		return nil, nil, nil, err
	}
	for _, p := range proxies {
		proxyNames = append(proxyNames, p.GetBaseConfig().Name)
	}
	return result.Common, aggregator, proxyNames, nil
}

func safeError(err error, configPath string) error {
	if err == nil {
		return nil
	}
	message := err.Error()
	if configPath != "" {
		message = strings.ReplaceAll(message, configPath, "configuration")
	}
	if configPath != "" {
		if raw, readErr := os.ReadFile(configPath); readErr == nil {
			re := regexp.MustCompile(`(?im)^\s*(?:auth\.)?(?:token|password|secretKey)\s*=\s*["']?([^"'#\r\n]+)`)
			for _, match := range re.FindAllStringSubmatch(string(raw), -1) {
				secret := strings.TrimSpace(match[1])
				if secret != "" {
					message = strings.ReplaceAll(message, secret, "[redacted]")
				}
			}
		}
	}
	return fmt.Errorf("%s", message)
}
