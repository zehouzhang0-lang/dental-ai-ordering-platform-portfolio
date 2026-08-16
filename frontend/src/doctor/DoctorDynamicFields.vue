<script setup lang="ts">
import type { ProductOption } from './types/contracts'

const props = defineProps<{
  fields: ProductOption['form_fields']
  modelValue: Record<string, string>
}>()

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, string>]
}>()

function setValue(key: string, event: Event) {
  const target = event.target as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement
  emit('update:modelValue', { ...props.modelValue, [key]: target.value })
}
</script>

<template>
  <div class="dv2-form-stack">
    <label v-for="configField in fields" :key="configField.key">
      <span>{{ configField.label }} <i v-if="configField.required">*</i></span>
      <select
        v-if="configField.type === 'SELECT'"
        :value="modelValue[configField.key]"
        @change="setValue(configField.key, $event)"
      >
        <option value="">请选择</option>
        <option v-for="selectOption in configField.options || []" :key="selectOption" :value="selectOption">
          {{ selectOption }}
        </option>
      </select>
      <textarea
        v-else-if="configField.type === 'TEXTAREA'"
        :value="modelValue[configField.key]"
        rows="4"
        @input="setValue(configField.key, $event)"
      />
      <input
        v-else
        :value="modelValue[configField.key]"
        :type="configField.type === 'NUMBER' ? 'number' : 'text'"
        @input="setValue(configField.key, $event)"
      >
    </label>
  </div>
</template>
