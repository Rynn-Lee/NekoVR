import plugin from 'tailwindcss/plugin';
import forms from '@tailwindcss/forms';
import typography from '@tailwindcss/typography';
import gradient from 'tailwind-gradient-mask-image';
import type { Config } from 'tailwindcss';
import { transform } from 'typescript';

const colors = {
  'dark-orange': {
    primary: '#f97316',
    hover: '#fb923c',
    bg: '#1b1b1f',
    card: '#24242a',
    subtle: '#464650',
  },
  'dark-purple': {
    primary: '#a855f7',
    hover: '#c084fc',
    bg: '#1d172a',
    card: '#271f38',
    subtle: '#4e3e6e',
  },
  'dark-gray': {
    primary: '#d1d5db',
    hover: '#f3f4f6',
    bg: '#181a1f',
    card: '#21252b',
    subtle: '#4b5563',
  },
  'light-clean': {
    primary: '#0f172a',
    hover: '#1e293b',
    bg: '#ffffff',
    card: '#f1f5f9',
    subtle: '#cbd5e1',
  },
};

const config = {
  content: ['./src/**/*.{js,jsx,ts,tsx}'],
  theme: {
    screens: {
      'mobile-settings': { raw: 'not (min-width: 900px)' },
      nsmol: { raw: 'not (min-width: 525px)' },
      smol: '525px',
      mobile: { raw: 'not (min-width: 800px)' },
      'xs-settings': '900px',
      xs: '800px',
      nsm: { raw: 'not (min-width: 900px)' },
      sm: '900px',
      md: '1100px',
      nmd: { raw: 'not (min-width: 1100px)' },
      'md-max': { raw: 'not (min-width: 1100px)' },
      lg: '1300px',
      xl: '1600px',
      tall: { raw: '(min-height: 860px)' },
    },
    extend: {
      colors: {
        status: {
          success: 'rgb(var(--success), <alpha-value>)',
          warning: 'rgb(var(--warning), <alpha-value>)',
          critical: 'rgb(var(--critical), <alpha-value>)',
          special: 'rgb(var(--special), <alpha-value>)',
        },
        window: {
          icon: 'rgb(var(--window-icon-stroke), <alpha-value>)',
        },
        ...colors,
        background: {
          10: 'rgb(var(--background-10), <alpha-value>)',
          20: 'rgb(var(--background-20), <alpha-value>)',
          30: 'rgb(var(--background-30), <alpha-value>)',
          40: 'rgb(var(--background-40), <alpha-value>)',
          50: 'rgb(var(--background-50), <alpha-value>)',
          60: 'rgb(var(--background-60), <alpha-value>)',
          70: 'rgb(var(--background-70), <alpha-value>)',
          80: 'rgb(var(--background-80), <alpha-value>)',
          90: 'rgb(var(--background-90), <alpha-value>)',
        },
        'accent-background': {
          10: 'rgb(var(--accent-background-10), <alpha-value>)',
          20: 'rgb(var(--accent-background-20), <alpha-value>)',
          30: 'rgb(var(--accent-background-30), <alpha-value>)',
          40: 'rgb(var(--accent-background-40), <alpha-value>)',
          50: 'rgb(var(--accent-background-50), <alpha-value>)',
        },
      },
      fontSize: {
        DEFAULT: 'calc(var(--font-size-standard) / 16)',
      },
      fontWeight: {
        DEFAULT: '500',
      },
      color: {
        DEFAULT: 'rgb(var(--default-color), <alpha-value>)',
      },
      keyframes: {
        bounce: {
          '0%, 100%': {
            transform: 'translateY(0)',
            'animation-timing-function': 'cubic-bezier(0, 0, 0.2, 1)',
          },
          '50%': {
            transform: 'translateY(-25%)',
            'animation-timing-function': 'cubic-bezier(0.8, 0, 1, 1)',
          },
        },
        'timer-tick': {
          "0%, 40%": {
            transform: 'scale(1)',
          },
          "20%": {
            transform: 'scale(1.3)',
          },
        },
        'spin-ccw': {
          '0%': {
            transform: 'rotate(0deg)',
          },
          '100%': {
            transform: 'rotate(-360deg)',
          },
        },
        skiing: {
          '0%, 100%': {
            transform: 'rotate(0deg) translateX(0%) translateY(0%)',
          },
          '10%': {
            transform: 'rotate(12deg) translateX(-5%) translateY(5%)',
          },
          '20%': {
            transform: 'rotate(10deg) translateX(0%) translateY(0%)',
          },
          '30%': {
            transform: 'rotate(12deg) translateX(5%) translateY(-5%)',
          },
          '40%': {
            transform: 'rotate(10deg) translateX(0%) translateY(0%)',
          },
          '50%': {
            transform: 'rotate(12deg) translateX(-5%) translateY(5%)',
          },
          '60%': {
            transform: 'rotate(10deg) translateX(0%) translateY(0%)',
          },
          '70%': {
            transform: 'rotate(12deg) translateX(5%) translateY(-5%)',
          },
          '80%': {
            transform: 'rotate(10deg) translateX(0%) translateY(0%)',
          },
          '90%': {
            transform: 'rotate(10deg) translateX(-5%) translateY(5%)',
          },
        },
      },
      backgroundImage: {
        'dark-orange': `linear-gradient(135deg, #f97316 50%, #1b1b1f 50% 100%)`,
        'dark-purple': `linear-gradient(135deg, #a855f7 50%, #1d172a 50% 100%)`,
        'dark-gray': `linear-gradient(135deg, #d1d5db 50%, #181a1f 50% 100%)`,
        'light-clean': `linear-gradient(135deg, #cbd5e1 50%, #ffffff 50% 100%)`,
      },
      animation: {
        'spin-ccw': 'spin-ccw 1s linear infinite',
        'timer-tick': 'timer-tick 1s linear infinite',
        skiing: 'skiing 1s linear infinite',
      },
    },
    data: {
      checked: 'checked=true',
    },
  },
  plugins: [
    forms,
    gradient,
    typography,
    plugin(function ({ addUtilities }) {
      const textConfig = (fontSize: any, fontWeight: any) => ({
        fontSize,
        fontWeight,
      });

      addUtilities({
        '.text-main-title': textConfig('calc(var(--font-size-title) / 16)', 700),
        '.text-section-title': textConfig('calc(var(--font-size-vr) / 16)', 700),
        '.text-standard': textConfig('calc(var(--font-size-standard) / 16)', 500),
        '.text-vr-accesible': textConfig('calc(var(--font-size-vr) / 16)', 500),
        '.text-vr-accesible-bold': textConfig('calc(var(--font-size-vr) / 16)', 700),
        '.text-standard-bold': textConfig('calc(var(--font-size-standard) / 16)', 700),
      });
    }),
    plugin(function ({ addVariant }) {
      addVariant('checked-hover', ['&:hover', '&[data-checked=true]']);
    }),
  ],
} satisfies Config;

export default config;
