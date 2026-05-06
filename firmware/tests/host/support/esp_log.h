#ifndef ESP_LOG_H
#define ESP_LOG_H

#include <stdarg.h>

static inline void esp_log_stub(const char *tag, const char *fmt, ...)
{
    va_list args;
    (void)tag;
    (void)fmt;
    va_start(args, fmt);
    va_end(args);
}

#define ESP_LOGD(...) do { esp_log_stub(__VA_ARGS__); } while (0)
#define ESP_LOGI(...) do { esp_log_stub(__VA_ARGS__); } while (0)
#define ESP_LOGW(...) do { esp_log_stub(__VA_ARGS__); } while (0)
#define ESP_LOGE(...) do { esp_log_stub(__VA_ARGS__); } while (0)

#endif
