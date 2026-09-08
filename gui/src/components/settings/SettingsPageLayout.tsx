import classNames from 'classnames';
import React, {
  createContext,
  ReactNode,
  useContext,
  useEffect,
  useRef,
  useState,
} from 'react';
import { useLocation } from 'react-router-dom';
import { ArrowDownIcon } from '@/components/commons/icon/ArrowIcons';

interface SettingsLayoutContextType {
  collapsedMap: Record<string, boolean>;
  togglePane: (id: string) => void;
  collapseAll: () => void;
  expandAll: () => void;
}

const SettingsLayoutContext = createContext<SettingsLayoutContextType | null>(
  null
);

export function useSettingsLayout() {
  return useContext(SettingsLayoutContext);
}

export function SettingsPageLayout({
  children,
  className,
  ...props
}: {
  children: ReactNode;
} & React.HTMLAttributes<HTMLDivElement>) {
  const pageRef = useRef<HTMLDivElement | null>(null);
  const { state } = useLocation();
  const [collapsedMap, setCollapsedMap] = useState<Record<string, boolean>>({});

  const togglePane = (id: string) => {
    setCollapsedMap((prev) => ({
      ...prev,
      [id]: !prev[id],
    }));
  };

  const collapseAll = () => {
    if (!pageRef.current) return;
    const panes = pageRef.current.querySelectorAll('[data-settings-pane-id]');
    const newMap: Record<string, boolean> = {};
    panes.forEach((p) => {
      const paneId = p.getAttribute('data-settings-pane-id');
      if (paneId) newMap[paneId] = true;
    });
    setCollapsedMap(newMap);
  };

  const expandAll = () => {
    setCollapsedMap({});
  };

  useEffect(() => {
    const typedState: { scrollTo?: string } = state as any;
    if (!pageRef.current || !typedState || !typedState.scrollTo) {
      return;
    }
    const scrollToId = typedState.scrollTo;
    // Auto-expand the target pane
    setCollapsedMap((prev) => ({
      ...prev,
      [scrollToId]: false,
    }));

    const elem = pageRef.current.querySelector(
      `#${scrollToId}`
    ) as HTMLElement | null;
    if (elem) {
      setTimeout(() => {
        elem.scrollIntoView({
          block: 'start',
          behavior: 'smooth',
        });
      }, 50);
    }
  }, [state]);

  return (
    <SettingsLayoutContext.Provider
      value={{
        collapsedMap,
        togglePane,
        collapseAll,
        expandAll,
      }}
    >
      <div
        ref={pageRef}
        className={classNames('flex flex-col gap-3 p-1', className)}
        {...props}
      >
        <div className="flex justify-end gap-2 px-1 pb-1">
          <button
            type="button"
            onClick={expandAll}
            className="text-xs font-semibold px-3 py-1.5 rounded-lg bg-background-60 hover:bg-background-50 text-background-20 hover:text-background-10 transition-colors border border-background-50 cursor-pointer shadow-sm"
          >
            Развернуть все
          </button>
          <button
            type="button"
            onClick={collapseAll}
            className="text-xs font-semibold px-3 py-1.5 rounded-lg bg-background-60 hover:bg-background-50 text-background-20 hover:text-background-10 transition-colors border border-background-50 cursor-pointer shadow-sm"
          >
            Свернуть все
          </button>
        </div>
        {children}
      </div>
    </SettingsLayoutContext.Provider>
  );
}

export function SettingsPagePaneLayout({
  children,
  className,
  icon,
  id,
  defaultCollapsed = false,
  ...props
}: {
  children: ReactNode;
  icon: ReactNode;
  id?: string;
  defaultCollapsed?: boolean;
} & React.HTMLAttributes<HTMLDivElement>) {
  const ctx = useSettingsLayout();
  const [localCollapsed, setLocalCollapsed] = useState(defaultCollapsed);

  const isCollapsed =
    id && ctx && ctx.collapsedMap[id] !== undefined
      ? ctx.collapsedMap[id]
      : localCollapsed;

  const toggle = () => {
    if (id && ctx) {
      ctx.togglePane(id);
    } else {
      setLocalCollapsed((prev) => !prev);
    }
  };

  // Safely extract first child as title if it exists
  const childArray =
    React.isValidElement(children) && children.type === React.Fragment
      ? React.Children.toArray((children.props as any).children)
      : React.Children.toArray(children);

  const hasTitle = childArray.length > 1;
  const titleElement = hasTitle ? childArray[0] : null;
  const bodyElements = hasTitle ? childArray.slice(1) : childArray;

  return (
    <div
      id={id}
      data-settings-pane-id={id || 'pane'}
      className={classNames(
        'bg-background-70 border border-background-50 rounded-2xl p-5 w-full relative scroll-mt-14 mobile:scroll-mt-20',
        'transition-all duration-200 shadow-sm',
        className
      )}
      {...props}
    >
      <div
        className="flex items-center justify-between gap-4 cursor-pointer select-none group"
        onClick={toggle}
      >
        <div className="flex items-center gap-3.5 flex-grow min-w-0">
          <div className="w-11 h-11 min-w-[44px] bg-accent-background-50 border border-accent-background-40 flex justify-center items-center rounded-xl fill-accent-background-10 text-accent-background-10 transition-transform group-hover:scale-105 shadow-inner">
            {icon}
          </div>
          <div className="flex flex-col min-w-0 flex-grow">
            {titleElement ? (
              <div className="pointer-events-none [&>h1]:!mb-0 [&>h2]:!mb-0 [&>p]:!mb-0 [&>div]:!mb-0">
                {titleElement}
              </div>
            ) : (
              <span className="text-standard-bold text-background-10">
                Настройки
              </span>
            )}
          </div>
        </div>

        <div className="flex items-center gap-2 flex-shrink-0">
          <div
            className={classNames(
              'w-8 h-8 rounded-lg bg-background-60 group-hover:bg-background-50 text-background-20 group-hover:text-background-10 flex items-center justify-center transition-all border border-background-50'
            )}
          >
            <div
              className={classNames(
                'transition-transform duration-200 fill-current',
                isCollapsed ? 'rotate-0' : 'rotate-180'
              )}
            >
              <ArrowDownIcon size={18} />
            </div>
          </div>
        </div>
      </div>

      <div
        className={classNames(
          'transition-all duration-300 ease-in-out',
          isCollapsed
            ? 'hidden'
            : 'block pt-4 border-t border-background-50 mt-4'
        )}
      >
        {bodyElements}
      </div>
    </div>
  );
}
