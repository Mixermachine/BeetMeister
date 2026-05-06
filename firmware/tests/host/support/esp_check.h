#ifndef ESP_CHECK_H
#define ESP_CHECK_H
#include "esp_err.h"
#define ESP_RETURN_ON_FALSE(cond, err, tag, msg) do { if (!(cond)) { return (err); } } while (0)
#define ESP_RETURN_ON_ERROR(expr, tag, msg) do { esp_err_t rc__ = (expr); if (rc__ != ESP_OK) { return rc__; } } while (0)
#endif
