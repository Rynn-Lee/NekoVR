import classNames from 'classnames';
import { Control, Controller, FieldPath, FieldValues } from 'react-hook-form';

export function ThemeSelector<T extends FieldValues = FieldValues>({
  control,
  name,
  value,
  disabled,
  colors,
  label,
  description,
  ...props
}: {
  control: Control<T>;
  name: FieldPath<T>;
  colors: string | undefined;
  value: string;
  label?: string;
  description?: string;
} & React.HTMLProps<HTMLInputElement>) {
  return (
    <Controller
      control={control}
      name={name}
      render={({ field: { onChange, ref, name, value: checked } }) => {
        const isSelected = value === checked;
        return (
          <label
            className={classNames(
              'flex flex-col items-center gap-2 p-3 rounded-2xl cursor-pointer transition-all duration-200',
              'border-2 select-none hover:scale-[1.02] min-w-[140px]',
              isSelected
                ? 'border-accent-background-30 bg-background-60 shadow-lg ring-1 ring-accent-background-30'
                : 'border-background-50 bg-background-70 hover:border-background-40 hover:bg-background-60'
            )}
          >
            <div className="relative">
              <input
                type="radio"
                className={classNames(
                  colors,
                  'focus:ring-transparent focus:ring-offset-transparent focus:outline-transparent',
                  'appearance-none rounded-xl w-24 h-16 shadow-inner transition-all',
                  'border border-background-40 cursor-pointer',
                  isSelected && 'ring-2 ring-accent-background-30'
                )}
                style={{
                  WebkitTextFillColor: 'transparent',
                }}
                name={name}
                ref={ref}
                onChange={onChange}
                value={value}
                checked={isSelected}
                disabled={disabled}
                {...props}
              />
            </div>
            {label && (
              <span
                className={classNames(
                  'text-sm font-semibold text-center tracking-wide',
                  isSelected ? 'text-background-10 font-bold' : 'text-background-20'
                )}
              >
                {label}
              </span>
            )}
            {description && (
              <span className="text-xs text-background-30 text-center max-w-[130px]">
                {description}
              </span>
            )}
          </label>
        );
      }}
    />
  );
}
