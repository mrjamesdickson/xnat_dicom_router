import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  define: {
    '__BUILD_TIME__': JSON.stringify(new Date().toISOString())
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: '../src/main/resources/META-INF/resources/admin',
    emptyOutDir: true,
    // Ensure all assets are bundled locally - no CDN references
    rollupOptions: {
      output: {
        // Keep all chunks in the assets folder
        assetFileNames: 'assets/[name]-[hash][extname]',
        chunkFileNames: 'assets/[name]-[hash].js',
        entryFileNames: 'assets/[name]-[hash].js'
      }
    }
  },
  // Don't externalize any dependencies - bundle everything
  optimizeDeps: {
    include: ['react', 'react-dom', 'react-router-dom']
  }
})
