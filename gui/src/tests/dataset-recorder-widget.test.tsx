import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { LocalizedProps } from '@fluent/react';
import { ButtonHTMLAttributes, PropsWithChildren, ReactNode } from 'react';
import {
  CancelDatasetRecordingRequestT,
  DatasetActionResponseT,
  DatasetErrorCode,
  DatasetExportRequestT,
  DatasetListResponseT,
  DatasetOperation,
  DatasetReadinessFindingT,
  DatasetReadinessSeverity,
  DatasetRecordingState,
  DatasetRecordingStatusResponseT,
  DatasetRevealRequestT,
  DatasetSessionInfoT,
  DatasetValidateRequestT,
  DatasetValidateResponseT,
  RpcMessage,
  StartDatasetRecordingRequestT,
  StopDatasetRecordingRequestT,
} from 'solarxr-protocol';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DatasetRecorderWidget } from '@/components/ai-drift/DatasetRecorderWidget';
import { useDatasetRecorder } from '@/hooks/dataset-recorder';

type PacketHandler = (packet: unknown, transactionId?: number) => void;

const runtime = vi.hoisted(() => {
  const handlers = new Map<number, PacketHandler>();
  const requests: Array<{
    type: RpcMessage;
    payload: unknown;
    transactionId: number;
  }> = [];
  let nextTransactionId = 0;
  return {
    handlers,
    requests,
    reset() {
      handlers.clear();
      requests.length = 0;
      nextTransactionId = 0;
    },
    sendRPCPacket: vi.fn((type: RpcMessage, payload: unknown) => {
      const transactionId = ++nextTransactionId;
      requests.push({ type, payload, transactionId });
      return transactionId;
    }),
    useRPCPacket: vi.fn((type: RpcMessage, handler: PacketHandler) => {
      handlers.set(type, handler);
    }),
    revealDataset: vi.fn(async () => true),
    exportDataset: vi.fn(async () => ({ success: true })),
    openDatasetsFolder: vi.fn(async () => true),
  };
});

vi.mock('@/hooks/websocket-api', () => ({
  useWebsocketAPI: () => ({
    useRPCPacket: runtime.useRPCPacket,
    sendRPCPacket: runtime.sendRPCPacket,
    isConnected: true,
  }),
}));

vi.mock('@/hooks/timeout', () => ({ useInterval: () => undefined }));

vi.mock('@/hooks/electron', () => ({
  useElectron: () => ({
    isElectron: true,
    api: {
      revealDataset: runtime.revealDataset,
      exportDataset: runtime.exportDataset,
      openDatasetsFolder: runtime.openDatasetsFolder,
    },
  }),
}));

vi.mock('@fluent/react', () => ({
  useLocalization: () => ({ l10n: { getString: (id: string) => id } }),
  Localized: ({ children }: PropsWithChildren<LocalizedProps>) => children,
}));

vi.mock('@/components/commons/Button', () => ({
  Button: ({
    children,
    icon,
    loading: _loading,
    variant: _variant,
    ...props
  }: PropsWithChildren<
    ButtonHTMLAttributes<HTMLButtonElement> & {
      icon?: ReactNode;
      loading?: boolean;
      variant?: string;
    }
  >) => (
    <button type="button" {...props}>
      {icon}
      {children}
    </button>
  ),
}));

vi.mock('@/components/commons/Typography', () => ({
  Typography: ({ children }: PropsWithChildren) => <div>{children}</div>,
}));

vi.mock('@/components/commons/ProgressBar', () => ({
  ProgressBar: ({ progress }: { progress: number }) => (
    <div role="progressbar" aria-valuenow={progress * 100} />
  ),
}));

vi.mock('@/components/commons/icon/FolderIcon', () => ({
  FolderIcon: () => <span aria-hidden="true" />,
}));
vi.mock('@/components/commons/icon/WarningIcon', () => ({
  WarningIcon: () => <span aria-hidden="true" />,
}));
vi.mock('@/components/commons/icon/CheckIcon', () => ({
  CheckIcon: () => <span aria-hidden="true" />,
}));
vi.mock('@/components/commons/icon/TrashIcon', () => ({
  TrashIcon: () => <span aria-hidden="true" />,
}));
vi.mock('@/components/commons/icon/DownloadIcon', () => ({
  DownloadIcon: () => <span aria-hidden="true" />,
}));

function MountedRecorder() {
  return <DatasetRecorderWidget control={useDatasetRecorder()} />;
}

function status(
  state: DatasetRecordingState,
  version: bigint,
  sessionId = 'session-mounted',
  datasetReady = true,
  readinessReason = 'direct evidence accepted'
) {
  const response = new DatasetRecordingStatusResponseT();
  response.state = state;
  response.statusVersion = version;
  response.sessionId = sessionId;
  response.sampledFrames =
    state === DatasetRecordingState.RECORDING ? 125n : 0n;
  response.writtenFrames =
    state === DatasetRecordingState.COMPLETED ? 125n : 0n;
  response.finalizationProgress =
    state === DatasetRecordingState.FINALIZING ? 0.5 : 0;
  response.readiness = datasetReady
    ? [
        new DatasetReadinessFindingT(
          'DATASET_READY',
          readinessReason,
          DatasetReadinessSeverity.INFO
        ),
      ]
    : [
        new DatasetReadinessFindingT(
          'DATASET_READY_GATE_PENDING',
          readinessReason,
          DatasetReadinessSeverity.WARNING
        ),
      ];
  return response;
}

function lastRequest(type: RpcMessage) {
  const request = runtime.requests.findLast(
    (candidate) => candidate.type === type
  );
  expect(request).toBeDefined();
  return request!;
}

async function emit(type: RpcMessage, packet: unknown, transactionId?: number) {
  const handler = runtime.handlers.get(type);
  expect(handler).toBeDefined();
  handler!(packet, transactionId);
  await Promise.resolve();
}

describe('mounted dataset recorder widget RPC lifecycle', () => {
  afterEach(cleanup);

  beforeEach(() => {
    runtime.reset();
    runtime.sendRPCPacket.mockClear();
    runtime.useRPCPacket.mockClear();
    runtime.revealDataset.mockClear();
    runtime.exportDataset.mockClear();
    Object.assign(window, {
      electronAPI: {
        revealDataset: runtime.revealDataset,
        exportDataset: runtime.exportDataset,
        openDatasetsFolder: runtime.openDatasetsFolder,
      },
    });
  });

  it('renders rejected and accepted server reports and sends production only after authoritative readiness', async () => {
    render(<MountedRecorder />);
    await waitFor(() =>
      expect(runtime.requests.length).toBeGreaterThanOrEqual(2)
    );

    await emit(
      RpcMessage.DatasetRecordingStatusResponse,
      status(
        DatasetRecordingState.IDLE,
        1n,
        '',
        false,
        'Pilot archive bytes changed'
      )
    );
    const production = screen.getByRole('button', {
      name: 'dataset_recorder-profile-production',
    });
    await waitFor(() => {
      expect((production as HTMLButtonElement).disabled).toBe(true);
      expect(
        screen.queryAllByText('Pilot archive bytes changed').length
      ).toBeGreaterThan(0);
    });

    await emit(
      RpcMessage.DatasetRecordingStatusResponse,
      status(DatasetRecordingState.IDLE, 2n, '', true)
    );
    await waitFor(() =>
      expect((production as HTMLButtonElement).disabled).toBe(false)
    );
    fireEvent.click(production);
    fireEvent.click(
      screen.getByRole('checkbox', { name: 'dataset_recorder-consent-label' })
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'dataset_recorder-action-start' })
    );

    const start = lastRequest(RpcMessage.StartDatasetRecordingRequest);
    expect((start.payload as StartDatasetRecordingRequestT).profile).toBe(1);
  });

  it('drives generated requests from authoritative status through finalize, inventory, validation, desktop authorization, and cancel', async () => {
    render(<MountedRecorder />);
    await waitFor(() =>
      expect(runtime.requests.length).toBeGreaterThanOrEqual(2)
    );
    await emit(
      RpcMessage.DatasetRecordingStatusResponse,
      status(DatasetRecordingState.IDLE, 1n)
    );

    fireEvent.click(
      screen.getByRole('checkbox', { name: 'dataset_recorder-consent-label' })
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'dataset_recorder-action-start' })
    );
    const start = lastRequest(RpcMessage.StartDatasetRecordingRequest);
    expect(start.payload).toBeInstanceOf(StartDatasetRecordingRequestT);
    expect((start.payload as StartDatasetRecordingRequestT).consent).toBe(true);
    await emit(
      RpcMessage.DatasetRecordingStatusResponse,
      status(DatasetRecordingState.STARTING, 2n),
      start.transactionId
    );
    await emit(
      RpcMessage.DatasetRecordingStatusResponse,
      status(DatasetRecordingState.RECORDING, 3n)
    );
    expect(screen.getByText('dataset_recorder-status-recording')).toBeTruthy();
    expect(screen.getByText('125')).toBeTruthy();

    fireEvent.click(
      screen.getByRole('button', { name: 'dataset_recorder-action-stop' })
    );
    const stop = lastRequest(RpcMessage.StopDatasetRecordingRequest);
    expect(stop.payload).toBeInstanceOf(StopDatasetRecordingRequestT);
    await emit(
      RpcMessage.DatasetRecordingStatusResponse,
      status(DatasetRecordingState.FINALIZING, 4n),
      stop.transactionId
    );
    expect(screen.getByText('50%')).toBeTruthy();
    await emit(
      RpcMessage.DatasetRecordingStatusResponse,
      status(DatasetRecordingState.COMPLETED, 5n)
    );

    const session = new DatasetSessionInfoT();
    session.sessionId = 'session-mounted';
    session.isValid = true;
    session.writtenFrames = 125n;
    await emit(
      RpcMessage.DatasetListResponse,
      new DatasetListResponseT([session], [], DatasetErrorCode.OK)
    );
    expect(screen.getByText('session-mounted.nvrdata')).toBeTruthy();

    fireEvent.click(
      screen.getByRole('button', { name: 'dataset_recorder-action-validate' })
    );
    expect(
      lastRequest(RpcMessage.DatasetValidateRequest).payload
    ).toBeInstanceOf(DatasetValidateRequestT);
    await emit(
      RpcMessage.DatasetValidateResponse,
      new DatasetValidateResponseT('session-mounted', true)
    );

    fireEvent.click(
      screen.getByRole('button', { name: 'dataset_recorder-action-reveal' })
    );
    const reveal = lastRequest(RpcMessage.DatasetRevealRequest);
    expect(reveal.payload).toBeInstanceOf(DatasetRevealRequestT);
    await emit(
      RpcMessage.DatasetActionResponse,
      new DatasetActionResponseT(
        'session-mounted',
        true,
        '',
        '',
        DatasetErrorCode.OK,
        DatasetOperation.REVEAL,
        6n
      ),
      reveal.transactionId
    );
    await waitFor(() =>
      expect(runtime.revealDataset).toHaveBeenCalledWith('session-mounted')
    );

    fireEvent.click(
      screen.getByRole('button', { name: 'dataset_recorder-action-export' })
    );
    const rejectedExport = lastRequest(RpcMessage.DatasetExportRequest);
    expect(rejectedExport.payload).toBeInstanceOf(DatasetExportRequestT);
    await emit(
      RpcMessage.DatasetActionResponse,
      new DatasetActionResponseT(
        'session-mounted',
        true,
        '',
        'renderer-supplied-path.nvrdata',
        DatasetErrorCode.OK,
        DatasetOperation.EXPORT,
        7n
      ),
      rejectedExport.transactionId
    );
    expect(runtime.exportDataset).not.toHaveBeenCalled();

    fireEvent.click(
      screen.getByRole('button', { name: 'dataset_recorder-action-export' })
    );
    const exportRequest = lastRequest(RpcMessage.DatasetExportRequest);
    await emit(
      RpcMessage.DatasetActionResponse,
      new DatasetActionResponseT(
        'session-mounted',
        true,
        '',
        '',
        DatasetErrorCode.OK,
        DatasetOperation.EXPORT,
        8n
      ),
      exportRequest.transactionId
    );
    await waitFor(() =>
      expect(runtime.exportDataset).toHaveBeenCalledWith('session-mounted')
    );

    fireEvent.click(
      screen.getByRole('button', { name: 'dataset_recorder-action-start' })
    );
    const secondStart = lastRequest(RpcMessage.StartDatasetRecordingRequest);
    await emit(
      RpcMessage.DatasetRecordingStatusResponse,
      status(DatasetRecordingState.RECORDING, 9n, 'session-cancelled'),
      secondStart.transactionId
    );
    fireEvent.click(
      screen.getByRole('button', { name: 'dataset_recorder-action-cancel' })
    );
    fireEvent.click(
      screen.getByRole('button', {
        name: 'dataset_recorder-action-confirm_cancel_button',
      })
    );
    const cancel = lastRequest(RpcMessage.CancelDatasetRecordingRequest);
    expect(cancel.payload).toBeInstanceOf(CancelDatasetRecordingRequestT);
    await emit(
      RpcMessage.DatasetRecordingStatusResponse,
      status(DatasetRecordingState.CANCELLED, 10n, 'session-cancelled'),
      cancel.transactionId
    );
    expect(screen.getByText('dataset_recorder-status-cancelled')).toBeTruthy();
  });
});
