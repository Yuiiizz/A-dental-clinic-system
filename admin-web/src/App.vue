<script setup lang="ts">
const classroomDemo = import.meta.env.VITE_CLASSROOM_DEMO === 'true';
</script>

<template>
  <div v-if="classroomDemo" class="classroom-banner" role="note">
    演示环境 · 页面中的人员及业务记录均为虚构
  </div>
  <router-view />
</template>

<style scoped>
.classroom-banner {
  background: #e6f4f0;
  border-bottom: 1px solid #c9e3da;
  color: #265e54;
  padding: 6px 18px;
  text-align: center;
  font-size: 12px;
}
</style>
