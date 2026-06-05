import { defineNoteConfig } from 'vuepress-theme-plume'

export const docsNotes = defineNoteConfig({
  dir: 'docs',
  link: '/docs/',
  sidebar: [
    {
      text: '快速上手',
      collapsed: true,
      items: [
        '项目介绍',
        '翻译使用',
      ],
    },
    {
      text: '翻译功能',
      prefix: 'translation',
      collapsed: true,
      items: [
        '翻译模式',
        'API配置',
        '个性化设置',
        '常见问题',
        '错误代码',
      ],
    },
    {
      text: '翻译API',
      prefix: 'translationapi',
      collapsed: true,
      items: [
        'ML Kit',
        'NLLB',
        '必应翻译',
        '小牛翻译',
        '聚合AI翻译',
        '火山引擎',
        'Azure AI 翻译',
        'DeepL 翻译',
        '百度翻译',
        '腾讯云',
        '自定义文本API',
        '自定义图片API',
      ],
    },
    {
      text: '漫画翻译',
      prefix: 'manga',
      collapsed: true,
      items: [
        '概述',
        '检测引擎',
        'OCR引擎',
      ],
    },
    {
      text: '杂项',
      prefix: 'notice',
      collapsed: true,
      items: [
        '切换UA',
        'test',
      ],
    },
  ],
})
