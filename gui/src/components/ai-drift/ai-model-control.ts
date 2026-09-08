import {
  AIExecutionProvider,
  AIModelConfigurationStateT,
  AITrackerSlotMappingT,
  AILegacyDriftMode,
  ModelConfigureRequestT,
} from 'solarxr-protocol';

export const AI_CONFIG_FIELD = {
  ENABLED: 1 << 0,
  PROVIDER: 1 << 1,
  CONTEXT: 1 << 2,
  CONFIDENCE: 1 << 3,
  LIMITS: 1 << 4,
  SMOOTHING: 1 << 5,
  STALE_DECAY: 1 << 6,
  LEGACY_MODE: 1 << 7,
  MAPPINGS: 1 << 8,
} as const;

export const AI_CONFIG_ADVANCED_MASK =
  AI_CONFIG_FIELD.PROVIDER |
  AI_CONFIG_FIELD.CONTEXT |
  AI_CONFIG_FIELD.CONFIDENCE |
  AI_CONFIG_FIELD.LIMITS |
  AI_CONFIG_FIELD.SMOOTHING |
  AI_CONFIG_FIELD.STALE_DECAY |
  AI_CONFIG_FIELD.LEGACY_MODE;

export type AIConfigurationDraft = {
  provider: AIExecutionProvider;
  contextFrames: number;
  confidenceThreshold: number;
  maximumCorrectionRadians: number;
  maximumRateRadiansPerSecond: number;
  maximumAccelerationRadiansPerSecondSquared: number;
  smoothing: number;
  staleDecaySeconds: number;
  legacyMode: AILegacyDriftMode;
};

export function configurationDraft(
  configuration: AIModelConfigurationStateT
): AIConfigurationDraft {
  return {
    provider: configuration.requestedProvider,
    contextFrames: configuration.contextFrames,
    confidenceThreshold: configuration.confidenceThreshold,
    maximumCorrectionRadians: configuration.maximumCorrectionRadians,
    maximumRateRadiansPerSecond: configuration.maximumRateRadiansPerSecond,
    maximumAccelerationRadiansPerSecondSquared:
      configuration.maximumAccelerationRadiansPerSecondSquared,
    smoothing: configuration.smoothing,
    staleDecaySeconds: configuration.staleDecaySeconds,
    legacyMode: configuration.legacyMode,
  };
}

export function configurationRequest(
  draft: AIConfigurationDraft,
  fieldMask = AI_CONFIG_ADVANCED_MASK
): ModelConfigureRequestT {
  return new ModelConfigureRequestT(
    null,
    fieldMask,
    false,
    draft.provider,
    draft.contextFrames,
    draft.confidenceThreshold,
    draft.maximumCorrectionRadians,
    draft.maximumRateRadiansPerSecond,
    draft.maximumAccelerationRadiansPerSecondSquared,
    draft.smoothing,
    draft.staleDecaySeconds,
    draft.legacyMode
  );
}

export function enabledRequest(enabled: boolean): ModelConfigureRequestT {
  const request = new ModelConfigureRequestT();
  request.fieldMask = AI_CONFIG_FIELD.ENABLED;
  request.enabled = enabled;
  return request;
}

export function mappingsRequest(
  mappings: AITrackerSlotMappingT[]
): ModelConfigureRequestT {
  const request = new ModelConfigureRequestT();
  request.fieldMask = AI_CONFIG_FIELD.MAPPINGS;
  request.mappings = mappings;
  return request;
}

export const modelText = (value: string | Uint8Array | null | undefined): string =>
  typeof value === 'string' ? value : '';
