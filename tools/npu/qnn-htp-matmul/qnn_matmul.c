#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "QnnInterface.h"
#include "QnnOpDef.h"

#ifndef QNN_TEST_N
#define QNN_TEST_N 32
#endif
enum { N = QNN_TEST_N, ELEMENTS = N * N };

static uint16_t a[ELEMENTS], b[ELEMENTS], c[ELEMENTS];
static float af[ELEMENTS], bf[ELEMENTS], cf[ELEMENTS];
static uint8_t au[ELEMENTS], bu[ELEMENTS], cu[ELEMENTS];
static uint32_t shape[2] = {N, N};

static int check(const char *stage, Qnn_ErrorHandle_t rc) {
    if (rc == QNN_SUCCESS) return 0;
    fprintf(stderr, "%s failed: %u (0x%x)\n", stage, (unsigned)rc, (unsigned)rc);
    return 1;
}

static Qnn_Tensor_t make_tensor(const char *name, Qnn_TensorType_t type,
                                Qnn_DataType_t data_type) {
    Qnn_Tensor_t t = QNN_TENSOR_INIT;
    t.v1.name = name;
    t.v1.type = type;
    t.v1.dataType = data_type;
    if (data_type == QNN_DATATYPE_UFIXED_POINT_8) {
        t.v1.quantizeParams.encodingDefinition = QNN_DEFINITION_DEFINED;
        t.v1.quantizeParams.quantizationEncoding = QNN_QUANTIZATION_ENCODING_SCALE_OFFSET;
        t.v1.quantizeParams.scaleOffsetEncoding.scale = 1.0f;
        t.v1.quantizeParams.scaleOffsetEncoding.offset = 0;
    }
    t.v1.rank = 2;
    t.v1.dimensions = shape;
    t.v1.memType = QNN_TENSORMEMTYPE_RAW;
    return t;
}

int main(int argc, char **argv) {
    if (argc != 2 || (strcmp(argv[1], "matmul") && strcmp(argv[1], "add") &&
                      strcmp(argv[1], "add-fp32") && strcmp(argv[1], "matmul-fp32") &&
                      strcmp(argv[1], "cast-to-fp16") && strcmp(argv[1], "cast-from-fp16") &&
                      strcmp(argv[1], "add-u8") && strcmp(argv[1], "matmul-u8") &&
                      strcmp(argv[1], "add-u8-pattern") && strcmp(argv[1], "matmul-u8-pattern"))) {
        fprintf(stderr, "usage: %s matmul|add|add-fp32|matmul-fp32|cast-to-fp16|cast-from-fp16|add-u8|matmul-u8|add-u8-pattern|matmul-u8-pattern\n", argv[0]);
        return 1;
    }
    const int matmul = !strcmp(argv[1], "matmul") || !strcmp(argv[1], "matmul-fp32") ||
                       !strcmp(argv[1], "matmul-u8") || !strcmp(argv[1], "matmul-u8-pattern");
    const int u8 = !strcmp(argv[1], "add-u8") || !strcmp(argv[1], "matmul-u8") ||
                   !strcmp(argv[1], "add-u8-pattern") || !strcmp(argv[1], "matmul-u8-pattern");
    const int pattern = !strcmp(argv[1], "add-u8-pattern") ||
                        !strcmp(argv[1], "matmul-u8-pattern");
    const int cast_to = !strcmp(argv[1], "cast-to-fp16");
    const int cast_from = !strcmp(argv[1], "cast-from-fp16");
    const int unary = cast_to || cast_from;
    const int input_fp32 = !strcmp(argv[1], "add-fp32") ||
                           !strcmp(argv[1], "matmul-fp32") || cast_to;
    const int output_fp32 = !strcmp(argv[1], "add-fp32") ||
                            !strcmp(argv[1], "matmul-fp32") || cast_from;
    const Qnn_DataType_t input_type = u8 ? QNN_DATATYPE_UFIXED_POINT_8 :
                                      (input_fp32 ? QNN_DATATYPE_FLOAT_32 : QNN_DATATYPE_FLOAT_16);
    const Qnn_DataType_t output_type = u8 ? QNN_DATATYPE_UFIXED_POINT_8 :
                                       (output_fp32 ? QNN_DATATYPE_FLOAT_32 : QNN_DATATYPE_FLOAT_16);
    void *library = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        fprintf(stderr, "dlopen libQnnHtp.so: %s\n", dlerror());
        return 1;
    }
    Qnn_ErrorHandle_t (*get_providers)(const QnnInterface_t***, uint32_t*) =
        dlsym(library, "QnnInterface_getProviders");
    if (!get_providers) {
        fprintf(stderr, "QnnInterface_getProviders not found\n");
        return 1;
    }
    const QnnInterface_t **providers = NULL;
    uint32_t provider_count = 0;
    if (check("getProviders", get_providers(&providers, &provider_count)) ||
        !providers || !provider_count) return 1;
    const QnnInterface_t *provider = providers[0];
    printf("QNN provider backend=%u core=%u.%u backend_api=%u.%u\n",
           provider->backendId, provider->apiVersion.coreApiVersion.major,
           provider->apiVersion.coreApiVersion.minor,
           provider->apiVersion.backendApiVersion.major,
           provider->apiVersion.backendApiVersion.minor);
    fflush(stdout);
    if (provider->apiVersion.coreApiVersion.major != QNN_API_VERSION_MAJOR ||
        provider->apiVersion.coreApiVersion.minor < QNN_API_VERSION_MINOR) {
        fprintf(stderr, "QNN interface is older than the build headers\n");
        return 1;
    }
    QNN_INTERFACE_VER_TYPE api = provider->QNN_INTERFACE_VER_NAME;
    Qnn_BackendHandle_t backend = NULL;
    Qnn_DeviceHandle_t device = NULL;
    Qnn_ContextHandle_t context = NULL;
    Qnn_GraphHandle_t graph = NULL;
    if (check("backendCreate", api.backendCreate(NULL, NULL, &backend))) return 1;
    if (check("deviceCreate", api.deviceCreate(NULL, NULL, &device))) return 1;
    if (check("contextCreate", api.contextCreate(backend, device, NULL, &context))) return 1;
    if (check("graphCreate", api.graphCreate(context, argv[1], NULL, &graph))) return 1;

    Qnn_Tensor_t inputs[2] = {
        make_tensor("A", QNN_TENSOR_TYPE_APP_WRITE, input_type),
        make_tensor("B", QNN_TENSOR_TYPE_STATIC, input_type),
    };
    Qnn_Tensor_t output = make_tensor("C", QNN_TENSOR_TYPE_APP_READ, output_type);
    for (unsigned i = 0; i < ELEMENTS; ++i) {
        b[i] = 0x3c00;
        bf[i] = 1.0f;
        bu[i] = pattern ? ((i / N * 7 + i % N) % 5 == 0) : 1;
    }
    inputs[1].v1.clientBuf.data = u8 ? (void *)bu : (input_fp32 ? (void *)bf : (void *)b);
    inputs[1].v1.clientBuf.dataSize = u8 ? sizeof(bu) : (input_fp32 ? sizeof(bf) : sizeof(b));
    if (check("tensorCreateGraphTensor(A)", api.tensorCreateGraphTensor(graph, &inputs[0])) ||
        (!unary && check("tensorCreateGraphTensor(B)", api.tensorCreateGraphTensor(graph, &inputs[1]))) ||
        check("tensorCreateGraphTensor(C)", api.tensorCreateGraphTensor(graph, &output))) return 1;

    Qnn_OpConfig_t op = QNN_OPCONFIG_INIT;
    op.v1.name = matmul ? "matmul" : "add";
    op.v1.packageName = QNN_OP_PACKAGE_NAME_QTI_AISW;
    op.v1.typeName = unary ? QNN_OP_CAST : (matmul ? QNN_OP_MAT_MUL : QNN_OP_ELEMENT_WISE_ADD);
    op.v1.numOfInputs = unary ? 1 : 2;
    op.v1.inputTensors = inputs;
    op.v1.numOfOutputs = 1;
    op.v1.outputTensors = &output;
    if (check("graphAddNode", api.graphAddNode(graph, op))) return 1;
    if (check("graphFinalize", api.graphFinalize(graph, NULL, NULL))) return 1;

    for (unsigned i = 0; i < ELEMENTS; ++i) {
        a[i] = 0x3c00; // FP16 1.
        c[i] = 0x7bff;        // Sentinel.
        af[i] = 1.0f;
        cf[i] = -12345.0f;
        au[i] = pattern ? ((i / N + i % N) % 3 == 0) : 1;
        cu[i] = 255;
    }
    inputs[0].v1.clientBuf.data = u8 ? (void *)au : (input_fp32 ? (void *)af : (void *)a);
    inputs[0].v1.clientBuf.dataSize = u8 ? sizeof(au) : (input_fp32 ? sizeof(af) : sizeof(a));
    output.v1.clientBuf.data = u8 ? (void *)cu : (output_fp32 ? (void *)cf : (void *)c);
    output.v1.clientBuf.dataSize = u8 ? sizeof(cu) : (output_fp32 ? sizeof(cf) : sizeof(c));
    if (u8)
        printf("tensor_ids A=%u B=%u C=%u input0=%u weight0=%u\n",
               inputs[0].v1.id, inputs[1].v1.id, output.v1.id, au[0], bu[0]);
    else if (input_fp32)
        printf("tensor_ids A=%u B=%u C=%u input0=%g weight0=%g\n",
               inputs[0].v1.id, inputs[1].v1.id, output.v1.id, af[0], bf[0]);
    else
        printf("tensor_ids A=%u B=%u C=%u input0=0x%04x weight0=0x%04x\n",
               inputs[0].v1.id, inputs[1].v1.id, output.v1.id, a[0], b[0]);
    fflush(stdout);
    if (check("graphExecute", api.graphExecute(graph, inputs, 1, &output, 1, NULL, NULL))) return 1;

    const uint16_t expected = unary ? 0x3c00 : (matmul ? (N == 128 ? 0x5800 : 0x5000) : 0x4000);
    const float expected_fp32 = unary ? 1.0f : (matmul ? (float)N : 2.0f);
    unsigned wrong = 0, zero = 0, sentinel = 0;
    float max_abs_error = 0.0f;
    for (unsigned i = 0; i < ELEMENTS; ++i) {
        unsigned expected_u8 = matmul ? N : 2;
        if (pattern) {
            const unsigned row = i / N, col = i % N;
            expected_u8 = matmul ? 0 : (unsigned)au[i] + bu[i];
            if (matmul)
                for (unsigned k = 0; k < N; ++k)
                    expected_u8 += (unsigned)au[row * N + k] * bu[k * N + col];
        }
        float delta = cf[i] - expected_fp32;
        if (delta < 0.0f) delta = -delta;
        if (delta > max_abs_error) max_abs_error = delta;
        wrong += u8 ? cu[i] != expected_u8 :
                 (output_fp32 ? delta > 1e-4f : c[i] != expected);
        zero += u8 ? cu[i] == 0 : (output_fp32 ? cf[i] == 0.0f : c[i] == 0);
        sentinel += u8 ? cu[i] == 255 :
                    (output_fp32 ? cf[i] == -12345.0f : c[i] == 0x7bff);
    }
    if (u8) {
        unsigned first_expected = matmul ? N : 2;
        if (pattern) {
            first_expected = matmul ? 0 : (unsigned)au[0] + bu[0];
            if (matmul)
                for (unsigned k = 0; k < N; ++k)
                    first_expected += (unsigned)au[k] * bu[k * N];
        }
        printf("op=%s n=%u result=%s first=%u expected=%u wrong=%u zero=%u sentinel=%u total=%u\n",
               argv[1], N, wrong ? "FAIL" : "PASS", cu[0], first_expected,
               wrong, zero, sentinel, ELEMENTS);
    }
    else if (output_fp32) {
        uint32_t first_bits = 0;
        memcpy(&first_bits, cf, sizeof(first_bits));
        printf("op=%s n=%u result=%s first=%.9g bits=0x%08x expected=%.9g max_abs_error=%.9g wrong=%u zero=%u sentinel=%u total=%u\n",
               argv[1], N, wrong ? "FAIL" : "PASS", cf[0], first_bits,
               (double)expected_fp32, (double)max_abs_error,
               wrong, zero, sentinel, ELEMENTS);
    }
    else
        printf("op=%s n=%u result=%s first=0x%04x expected=0x%04x wrong=%u zero=%u sentinel=%u total=%u\n",
               argv[1], N, wrong ? "FAIL" : "PASS", c[0], expected, wrong, zero, sentinel, ELEMENTS);
    if (context) api.contextFree(context, NULL);
    if (device) api.deviceFree(device);
    if (backend) api.backendFree(backend);
    return wrong ? 2 : 0;
}
