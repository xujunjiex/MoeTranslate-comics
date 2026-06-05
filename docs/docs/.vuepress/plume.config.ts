import { defineThemeConfig } from 'vuepress-theme-plume'
import { enNavbar, zhNavbar } from './navbar'
import { zhNotes, enNotes} from './notes/index.js'

/**
 * @see https://theme-plume.vuejs.press/config/basic/
 */
export default defineThemeConfig({
  logo: './logo.png',
  // your git repo url
  // docsRepo: '',
  // docsDir: 'docs',

  appearance: true,

  social: [
    { icon: 'github', link: 'https://github.com/xujunjiex/MoeTranslate-comics' },
  ],

  locales: {
    '/': {
      profile: {
        avatar: './logo.png',
        name: 'StarFlow',
        description: 'An Android Translate App.',
        // circle: true,
        // location: '',
        // organization: '',
      },

      navbar: zhNavbar,
      notes: zhNotes,
    },
    '/en/': {
      profile: {
        avatar: './logo.png',
        name: 'StarFlow',
        description: 'An Android Translate App.',
        // circle: true,
        // location: '',
        // organization: '',
      },

      navbar: enNavbar,
      notes: enNotes,
    },
  },
})
