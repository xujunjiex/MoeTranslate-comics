import { defineNotesConfig } from 'vuepress-theme-plume'
// import { plugins } from './plugins'
import { docsNotes } from './docs'

export const zhNotes = defineNotesConfig({
  dir: 'notes',
  link: '/',
  notes: [
    docsNotes,
  ],
})
