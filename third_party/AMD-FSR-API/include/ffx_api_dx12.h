#pragma once
#include "ffx_api.h"

struct ID3D12Device;

#define FFX_API_CREATE_CONTEXT_DESC_TYPE_BACKEND_DX12 FFX_API_MAKE_BACKEND_SUB_ID(FFX_API_BACKEND_ID_DX12, 0x02)

struct ffxCreateBackendDX12Desc {
    ffxCreateContextDescHeader header;
    ID3D12Device*              device;
};
