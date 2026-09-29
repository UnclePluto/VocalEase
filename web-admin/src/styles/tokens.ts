import type { ThemeConfig } from 'antd'

export const designTokens = {
  colorPrimary: '#1677ff',
  colorText: '#26262c',
  colorTextSecondary: '#8c8c8c',
  colorBorder: '#d9d9d9',
  colorSurface: '#ffffff',
  colorContentBackground: '#f5f5f5',
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
    fontFamily: '"Noto Sans SC", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif',
    fontSize: 14,
    controlHeight: 38,
  },
  components: {
    Layout: {
      headerBg: designTokens.colorSurface,
      siderBg: '#001529',
      bodyBg: designTokens.colorContentBackground,
      headerHeight: designTokens.headerHeight,
    },
    Menu: {
      itemHeight: 40,
      itemBorderRadius: 0,
      darkItemBg: '#001529',
      darkSubMenuItemBg: '#001529',
      darkItemColor: '#a6adb4',
      darkItemHoverColor: '#ffffff',
      darkItemSelectedBg: designTokens.colorPrimary,
      darkItemSelectedColor: '#ffffff',
    },
    Button: { fontWeight: 500 },
  },
}
