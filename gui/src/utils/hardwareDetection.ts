// Real Hardware GPU and System Detection Utility

export interface HardwareInfo {
  gpuName: string;
  vendor: 'NVIDIA' | 'AMD' | 'Intel' | 'Apple' | 'Unknown';
  provider: 'CUDA' | 'DirectML' | 'Metal' | 'CPU';
  isHardwareAccelerated: boolean;
}

export function detectHardwareGPU(): HardwareInfo {
  try {
    const canvas = document.createElement('canvas');
    const gl =
      canvas.getContext('webgl2') ||
      canvas.getContext('webgl') ||
      canvas.getContext('experimental-webgl');

    if (!gl) {
      return {
        gpuName: 'Аппаратный GPU не обнаружен',
        vendor: 'Unknown',
        provider: 'CPU',
        isHardwareAccelerated: false,
      };
    }

    const debugInfo = gl.getExtension('WEBGL_debug_renderer_info');
    let renderer = '';

    if (debugInfo) {
      renderer = gl.getParameter(debugInfo.UNMASKED_RENDERER_WEBGL) || '';
    }

    if (!renderer) {
      renderer = (gl.getParameter(gl.RENDERER) as string) || 'Generic GPU';
    }

    // Clean up driver prefixes (e.g. "ANGLE (NVIDIA, NVIDIA GeForce RTX 3080 ... Direct3D11 ...)")
    let cleanName = renderer;
    if (renderer.includes('(') && renderer.includes(')')) {
      const match = renderer.match(/\((.*?)\)/);
      if (match && match[1]) {
        cleanName = match[1];
      }
    }

    const upper = renderer.toUpperCase();

    if (upper.includes('NVIDIA') || upper.includes('GEFORCE') || upper.includes('RTX') || upper.includes('GTX')) {
      return {
        gpuName: cleanName,
        vendor: 'NVIDIA',
        provider: 'CUDA',
        isHardwareAccelerated: true,
      };
    }

    if (upper.includes('AMD') || upper.includes('RADEON') || upper.includes('ATI')) {
      return {
        gpuName: cleanName,
        vendor: 'AMD',
        provider: 'DirectML',
        isHardwareAccelerated: true,
      };
    }

    if (upper.includes('INTEL') || upper.includes('ARC') || upper.includes('IRIS') || upper.includes('UHD')) {
      return {
        gpuName: cleanName,
        vendor: 'Intel',
        provider: 'DirectML',
        isHardwareAccelerated: true,
      };
    }

    if (upper.includes('APPLE') || upper.includes('M1') || upper.includes('M2') || upper.includes('M3')) {
      return {
        gpuName: cleanName,
        vendor: 'Apple',
        provider: 'Metal',
        isHardwareAccelerated: true,
      };
    }

    return {
      gpuName: cleanName || 'Стандартный графический процессор',
      vendor: 'Unknown',
      provider: 'DirectML',
      isHardwareAccelerated: true,
    };
  } catch {
    return {
      gpuName: 'Системный видеоадаптер',
      vendor: 'Unknown',
      provider: 'CPU',
      isHardwareAccelerated: false,
    };
  }
}
