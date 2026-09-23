#include "ggml.h"
#include "ggml-backend.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

struct Case {
    const char * name;
    int size;
    ggml_type type = GGML_TYPE_F32;
    int m = 0;
    int k = 0;
    int n = 0;
};

static bool is_matmul(const Case & test) { return std::strncmp(test.name, "matmul", 6) == 0; }
static int rows(const Case & test) { return test.m ? test.m : test.size; }
static int depth(const Case & test) { return test.k ? test.k : test.size; }
static int cols(const Case & test) { return test.n ? test.n : test.size; }

static std::vector<float> run(const Case & test, const char * device_name, const char * library_path, bool & supported) {
    ggml_backend_load_all_from_path(library_path);
    ggml_backend_dev_t device = ggml_backend_dev_by_name(device_name);
    if (!device) {
        std::fprintf(stderr, "device %s unavailable\n", device_name);
        std::exit(2);
    }
    ggml_backend_t backend = ggml_backend_dev_init(device, nullptr);
    if (!backend) {
        std::fprintf(stderr, "device %s initialization failed\n", device_name);
        std::exit(2);
    }

    ggml_init_params params = { 16 * 1024 * 1024, nullptr, true };
    ggml_context * ctx = ggml_init(params);
    ggml_tensor * a;
    ggml_tensor * b = nullptr;
    ggml_tensor * output;
    if (is_matmul(test)) {
        a = ggml_new_tensor_2d(ctx, test.type, depth(test), cols(test));
        b = ggml_new_tensor_2d(ctx, GGML_TYPE_F32, depth(test), rows(test));
        output = ggml_mul_mat(ctx, a, b);
    } else {
        a = ggml_new_tensor_1d(ctx, GGML_TYPE_F32, test.size);
        if (std::strcmp(test.name, "add") == 0 || std::strcmp(test.name, "mul") == 0) {
            b = ggml_new_tensor_1d(ctx, GGML_TYPE_F32, test.size);
        }
        if (std::strcmp(test.name, "add") == 0) output = ggml_add(ctx, a, b);
        else if (std::strcmp(test.name, "mul") == 0) output = ggml_mul(ctx, a, b);
        else if (std::strcmp(test.name, "relu") == 0) output = ggml_relu(ctx, a);
        else if (std::strcmp(test.name, "rmsnorm") == 0) output = ggml_rms_norm(ctx, a, 1e-6f);
        else output = ggml_soft_max(ctx, a);
    }
    supported = ggml_backend_dev_supports_op(device, output);
    if (!supported) {
        ggml_free(ctx);
        ggml_backend_free(backend);
        return {};
    }
    ggml_cgraph * graph = ggml_new_graph(ctx);
    ggml_build_forward_expand(graph, output);
    ggml_backend_buffer_t buffer = ggml_backend_alloc_ctx_tensors(ctx, backend);
    if (!buffer) {
        std::fprintf(stderr, "%s: allocation failed\n", device_name);
        std::exit(3);
    }
    const size_t count = ggml_nelements(a);
    std::vector<float> input_a(count), input_b(b ? ggml_nelements(b) : 0);
    for (size_t i = 0; i < count; ++i) {
        input_a[i] = (static_cast<int>(i % 29) - 14) / 11.0f;
    }
    for (size_t i = 0; i < input_b.size(); ++i) {
        input_b[i] = (static_cast<int>((i * 7) % 31) - 15) / 13.0f;
    }
    if (test.type == GGML_TYPE_Q4_0) {
        std::vector<uint8_t> input_a_q4(ggml_nbytes(a));
        ggml_quantize_chunk(GGML_TYPE_Q4_0, input_a.data(), input_a_q4.data(),
                            0, cols(test), depth(test), nullptr);
        ggml_backend_tensor_set(a, input_a_q4.data(), 0, input_a_q4.size());
    } else if (test.type == GGML_TYPE_F16) {
        std::vector<ggml_fp16_t> input_a_f16(count);
        for (size_t i = 0; i < count; ++i) input_a_f16[i] = ggml_fp32_to_fp16(input_a[i]);
        ggml_backend_tensor_set(a, input_a_f16.data(), 0, count * sizeof(ggml_fp16_t));
    } else {
        ggml_backend_tensor_set(a, input_a.data(), 0, count * sizeof(float));
    }
    if (b) ggml_backend_tensor_set(b, input_b.data(), 0, input_b.size() * sizeof(float));
    ggml_status status = ggml_backend_graph_compute(backend, graph);
    if (status != GGML_STATUS_SUCCESS) {
        std::fprintf(stderr, "%s: graph_compute status=%d\n", device_name, static_cast<int>(status));
        std::exit(4);
    }
    ggml_backend_synchronize(backend);
    std::vector<float> result(ggml_nelements(output));
    ggml_backend_tensor_get(output, result.data(), 0, result.size() * sizeof(float));
    ggml_backend_buffer_free(buffer);
    ggml_free(ctx);
    ggml_backend_free(backend);
    return result;
}

int main(int argc, char ** argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    if (argc != 2) {
        std::fprintf(stderr, "usage: %s LIBRARY_DIR\n", argv[0]);
        return 2;
    }
    const Case cases[] = {{"add", 4096}, {"mul", 4096}, {"relu", 4096},
                          {"matmul", 16}, {"matmul", 17}, {"matmul", 24},
                          {"matmul", 31}, {"matmul", 32}, {"matmul", 33}, {"matmul", 64},
                          {"matmul", 96}, {"matmul", 128}, {"matmul", 192},
                          {"matmul", 31, GGML_TYPE_F16}, {"matmul", 32, GGML_TYPE_F16},
                          {"matmul", 33, GGML_TYPE_F16}, {"matmul", 64, GGML_TYPE_F16},
                          {"matmul", 32, GGML_TYPE_Q4_0}, {"matmul", 64, GGML_TYPE_Q4_0},
                          {"matmul", 128, GGML_TYPE_Q4_0},
                          {"matmul_rect", 0, GGML_TYPE_F32, 1, 32, 32},
                          {"matmul_rect", 0, GGML_TYPE_F32, 2, 32, 32},
                          {"matmul_rect", 0, GGML_TYPE_F32, 4, 32, 32},
                          {"matmul_rect", 0, GGML_TYPE_F32, 8, 32, 32},
                          {"matmul_rect", 0, GGML_TYPE_F32, 16, 32, 32},
                          {"matmul_rect", 0, GGML_TYPE_F32, 24, 32, 32},
                          {"matmul_rect", 0, GGML_TYPE_F32, 30, 32, 32},
                          {"matmul_rect", 0, GGML_TYPE_F32, 31, 32, 32},
                          {"matmul_rect", 0, GGML_TYPE_F32, 32, 31, 32},
                          {"matmul_rect", 0, GGML_TYPE_F32, 32, 32, 31},
                          {"matmul_rect", 0, GGML_TYPE_F32, 32, 32, 33},
                          {"matmul_rect", 0, GGML_TYPE_F32, 33, 32, 32},
                          {"matmul_rect", 0, GGML_TYPE_F32, 32, 33, 32},
                          {"matmul_rect", 0, GGML_TYPE_Q4_0, 1, 32, 32},
                          {"rmsnorm", 4096}, {"softmax", 4096}};
    int failed = 0;
    int tested = 0;
    for (const auto & test : cases) {
        bool cpu_supported = false, htp_supported = false;
        auto cpu = run(test, "CPU", argv[1], cpu_supported);
        if (!cpu_supported) {
            std::fprintf(stderr, "%s[%d]: CPU baseline unavailable\n", test.name, test.size);
            return 2;
        }
        auto htp = run(test, "HTP0", argv[1], htp_supported);
        if (!htp_supported) {
            std::printf("%s[%d] unsupported on HTP0\n", test.name, test.size);
            continue;
        }
        ++tested;
        const double tolerance = test.type == GGML_TYPE_Q4_0 ? 5e-2 :
                                 test.type == GGML_TYPE_F16 ? 1e-2 : 1e-3;
        double max_abs = 0, mean_abs = 0, max_rel = 0;
        int nonfinite = 0, mismatches = 0;
        size_t first_mismatch = cpu.size();
        for (size_t i = 0; i < cpu.size(); ++i) {
            if (!std::isfinite(htp[i])) { ++nonfinite; continue; }
            double abs_error = std::abs(static_cast<double>(cpu[i]) - htp[i]);
            max_abs = std::max(max_abs, abs_error);
            mean_abs += abs_error;
            max_rel = std::max(max_rel, abs_error / std::max(1e-6, std::abs(static_cast<double>(cpu[i]))));
            if (abs_error > tolerance) {
                ++mismatches;
                if (first_mismatch == cpu.size()) first_mismatch = i;
            }
        }
        mean_abs /= cpu.size();
        bool pass = nonfinite == 0 && max_abs <= tolerance;
        failed += !pass;
        const char * type_name = test.type == GGML_TYPE_Q4_0 ? "q4_0" :
                                 test.type == GGML_TYPE_F16 ? "f16" : "f32";
        std::printf("%s[%d,%d,%d,%s] %s max_abs=%.8g mean_abs=%.8g max_rel=%.8g nonfinite=%d mismatches=%d/%zu",
                    test.name, rows(test), depth(test), cols(test), type_name, pass ? "PASS" : "FAIL",
                    max_abs, mean_abs, max_rel, nonfinite, mismatches, cpu.size());
        if (first_mismatch < cpu.size()) {
            std::printf(" first=%zu cpu=%.8g htp=%.8g", first_mismatch, cpu[first_mismatch], htp[first_mismatch]);
        }
        std::printf("\n");
    }
    return (failed || !tested) ? 1 : 0;
}
