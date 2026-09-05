// Real Hardware GPU and System Detection Utility

export interface HardwareInfo {
  gpuName: string;
  vendor: 'NVIDIA' | 'AMD' | 'Intel' | 'Apple' | 'Unknown';
  provider: 'CUDA' | 'DirectML' | 'Metal' | 'CPU';
  isHardwareAccelerated: boolean;
}

export function cleanGpuName(raw: string): string {
  if (!raw) return 'Стандартный графический процессор';
  const s = raw.trim();

  // 1. NVIDIA Cards (e.g. "ANGLE (NVIDIA, NVIDIA GeForce RTX 5070 Ti (0x00002C05) Direct3D11 ...)" -> "NVIDIA GeForce RTX 5070 Ti")
  const nvidiaMatch =
    s.match(/NVIDIA\s+GeForce\s+(?:RTX|GTX|MX)?\s*[\w\s-]+(?=\s*\()/i) ||
    s.match(/NVIDIA\s+GeForce\s+(?:RTX|GTX|MX)?\s*[\w\s-]+/i) ||
    s.match(/GeForce\s+(?:RTX|GTX|MX)?\s*[\w\s-]+/i);
  if (nvidiaMatch) {
    let name = nvidiaMatch[0].trim();
    if (!name.startsWith('NVIDIA')) name = 'NVIDIA ' + name;
    name = name.replace(/\s+(?:Direct3D\d*|D3D\d*|OpenGL|Vulkan|vs_\d+_\d+|ps_\d+_\d+).*$/i, '');
    return name.replace(/\s+/g, ' ').trim();
  }

  // 2. AMD Cards (e.g. "ANGLE (AMD, AMD Radeon RX 7900 XTX (0x000073DF) ...)" -> "AMD Radeon RX 7900 XTX")
  const amdMatch =
    s.match(/AMD\s+Radeon\s+[\w\s-]+(?=\s*\()/i) ||
    s.match(/Radeon\s+[\w\s-]+(?=\s*\()/i) ||
    s.match(/AMD\s+Radeon\s+[\w\s-]+/i);
  if (amdMatch) {
    let name = amdMatch[0].trim();
    if (!name.startsWith('AMD')) name = 'AMD ' + name;
    name = name.replace(/\s+(?:Direct3D\d*|D3D\d*|OpenGL|Vulkan|vs_\d+_\d+|ps_\d+_\d+).*$/i, '');
    return name.replace(/\s+/g, ' ').trim();
  }

  // 3. Intel Cards (e.g. "ANGLE (Intel, Intel(R) Arc(TM) A770 Graphics (0x00005690) ...)" -> "Intel Arc A770 Graphics")
  const intelMatch =
    s.match(/Intel\(?R?\)?\s+(?:Arc|Iris|UHD|HD)\s*\(?TM?\)?\s*[\w\s-]+(?=\s*\()/i) ||
    s.match(/Intel\s+(?:Arc|Iris|UHD|HD)\s*[\w\s-]+/i);
  if (intelMatch) {
    let name = intelMatch[0].replace(/\((?:R|TM)\)/gi, '').trim();
    name = name.replace(/\s+(?:Direct3D\d*|D3D\d*|OpenGL|Vulkan|vs_\d+_\d+|ps_\d+_\d+).*$/i, '');
    return name.replace(/\s+/g, ' ').trim();
  }

  // 4. Apple Silicon (e.g. "Apple M2 Max")
  const appleMatch = s.match(/Apple\s+M\d+(?:\s+(?:Pro|Max|Ultra))?/i);
  if (appleMatch) {
    return appleMatch[0].trim();
  }

  // Fallback cleanup
  let fallback = s
    .replace(/^ANGLE\s*\((.*)\)$/i, '$1')
    .replace(/\(0x[0-9a-fA-F]+\)/g, '')
    .replace(/\((?:R|TM)\)/gi, '')
    .replace(/\b(Direct3D\d*|D3D\d*|OpenGL|Vulkan|vs_\d+_\d+|ps_\d+_\d+).*$/i, '')
    .trim();

  const parts = fallback.split(',').map((p) => p.trim()).filter(Boolean);
  if (parts.length > 1) {
    fallback = parts[parts.length - 1];
  }

  return fallback.replace(/\s+/g, ' ').trim() || 'Стандартный графический процессор';
}

export function detectHardwareGPU(): HardwareInfo {
  try {
    const canvas = document.createElement('canvas');
    const gl = (
      canvas.getContext('webgl2') ||
      canvas.getContext('webgl') ||
      canvas.getContext('experimental-webgl')
    ) as WebGLRenderingContext | WebGL2RenderingContext | null;

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

    const cleanName = cleanGpuName(renderer);
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
