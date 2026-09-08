import { useCallback, useEffect, useState } from 'react';
import {
  PersonalTrainingActionResponseT,
  PersonalTrainingEligibilityResponseT,
  PersonalTrainingOperation,
  PersonalTrainingPreset,
  PersonalTrainingProfilesResponseT,
  PersonalTrainingProvider,
  PersonalTrainingRequestT,
  PersonalTrainingResourcePolicyT,
  PersonalTrainingStatusResponseT,
  PersonalTrainingVrPolicy,
  RpcMessage,
} from 'solarxr-protocol';
import { useWebsocketAPI } from './websocket-api';
import { useInterval } from './timeout';

export const defaultPersonalResources = () =>
  new PersonalTrainingResourcePolicyT(
    4,
    4096,
    70,
    8192,
    8192,
    32,
    PersonalTrainingVrPolicy.PAUSE
  );

export function usePersonalTraining() {
  const { useRPCPacket, sendRPCPacket, isConnected } = useWebsocketAPI();
  const [profiles, setProfiles] = useState<PersonalTrainingProfilesResponseT | null>(
    null
  );
  const [eligibility, setEligibility] =
    useState<PersonalTrainingEligibilityResponseT | null>(null);
  const [status, setStatus] = useState<PersonalTrainingStatusResponseT | null>(null);
  const [lastAction, setLastAction] = useState<PersonalTrainingActionResponseT | null>(
    null
  );
  const [jobId, setJobId] = useState('');
  const request = useCallback(
    (
      operation: PersonalTrainingOperation,
      options: Partial<PersonalTrainingRequestT> = {}
    ) => {
      const payload = Object.assign(new PersonalTrainingRequestT(), options, {
        requestId: `personal-${Date.now()}-${Math.random().toString(16).slice(2)}`,
        operation,
      });
      return sendRPCPacket(RpcMessage.PersonalTrainingRequest, payload);
    },
    [sendRPCPacket]
  );

  useRPCPacket(RpcMessage.PersonalTrainingProfilesResponse, setProfiles);
  useRPCPacket(RpcMessage.PersonalTrainingEligibilityResponse, setEligibility);
  useRPCPacket(
    RpcMessage.PersonalTrainingStatusResponse,
    (value: PersonalTrainingStatusResponseT) => {
      setStatus(value);
      if (value.jobId) setJobId(String(value.jobId));
    }
  );
  useRPCPacket(
    RpcMessage.PersonalTrainingActionResponse,
    (value: PersonalTrainingActionResponseT) => {
      setLastAction(value);
      if (value.jobId) setJobId(String(value.jobId));
    }
  );

  const refresh = useCallback(() => {
    if (!isConnected) return;
    request(PersonalTrainingOperation.PROFILE_LIST);
    if (jobId) request(PersonalTrainingOperation.STATUS, { jobId });
  }, [isConnected, jobId, request]);
  useEffect(refresh, [refresh]);
  useInterval(refresh, isConnected ? 1000 : null);

  return {
    connected: isConnected,
    profiles: profiles?.profiles ?? [],
    eligibility,
    status,
    lastAction,
    request,
    refresh,
    jobId,
    checkEligibility: (
      profileId: string,
      baseModelSha256: string,
      sessions: string[]
    ) =>
      request(PersonalTrainingOperation.ELIGIBILITY, {
        profileId,
        baseModelSha256,
        sessionSha256: sessions,
      }),
    createJob: (
      profileId: string,
      baseModelSha256: string,
      sessions: string[],
      preset: PersonalTrainingPreset,
      provider: PersonalTrainingProvider,
      resources: PersonalTrainingResourcePolicyT
    ) =>
      request(PersonalTrainingOperation.JOB_CREATE, {
        profileId,
        baseModelSha256,
        sessionSha256: sessions,
        preset,
        provider,
        resources,
      }),
    jobAction: (operation: PersonalTrainingOperation) =>
      status &&
      request(operation, {
        jobId: String(status.jobId ?? jobId),
        expectedJobVersion: status.jobVersion,
      }),
  };
}
