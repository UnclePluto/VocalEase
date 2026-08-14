import type { ThemeConfig } from 'antd'

export const designTokens = {
  colorPrimary: '#356ae6',
  colorText: '#1f2633',
  colorTextSecondary: '#667085',
  colorBorder: '#e5e9f0',
  colorSurface: '#ffffff',
  colorContentBackground: '#f5f7fa',
  sidebarWidth: 208,
  headerHeight: 64,
  radius: 6,
} as const

export const antdTheme: ThemeConfig = {
  token: {
    colorPrimary: designTokens.colorPrimary,
    colorText: designTokens.colorText,
    colorTextSecondary: designTokens.colorTextSecondary,
    colorBorder: designTokens.colorBorder,
    colorBgContainer: designTokens.colorSurface,
    borderRadius: designTokens.radius,
    fontFamily: 'Inter, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif',
    fontSize: 14,
    controlHeight: 38,
  },
  components: {
    Layout: {
      headerBg: designTokens.colorSurface,
      siderBg: designTokens.colorSurface,
      bodyBg: designTokens.colorContentBackground,
      headerHeight: designTokens.headerHeight,
    },
    Menu: {
      itemHeight: 44,
      itemBorderRadius: 6,
      itemSelectedBg: '#eaf0ff',
      itemSelectedColor: designTokens.colorPrimary,
      itemHoverBg: '#f3f6fc',
    },
    Button: { fontWeight: 500 },
  },
}
