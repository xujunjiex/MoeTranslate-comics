import { defineNavbarConfig } from 'vuepress-theme-plume'

export const zhNavbar = defineNavbarConfig([
  {
    text: '首页',
    link: '/',
    icon: 'material-symbols:home'
  },
  {
    text: '文档',
    link: '/notes/docs/项目介绍.md',
    activeMatch: '^/docs/',
    icon: 'material-symbols:docs'
  },
  {
    text: '更多',
    icon: 'icon-park-outline:more-two',
    items: [
      {
        text: '支持项目',
        link: '/support/',
        icon: 'material-symbols:favorite'
      },
    ],
  },
])

export const enNavbar = defineNavbarConfig([
  {
    text: 'Home',
    link: '/en/',
    icon: 'material-symbols:home'
  },
  {
    text: 'Documentation',
    link: '/en/notes/docs/',
    icon: 'material-symbols:docs'
  },
  {
    text: 'More',
    icon: 'icon-park-outline:more-two',
    items: [
      {
        text: 'Support',
        link: '/en/support/',
        icon: 'material-symbols:favorite'
      },
    ],
  },
])