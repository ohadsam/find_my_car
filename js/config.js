export const CFG = Object.freeze({
  version:       '1.1.0',
  keys: Object.freeze({
    current: 'fmc_current_v1',
    history: 'fmc_history_v1',
    theme:   'fmc_theme_v1'
  }),
  maxHistory:    30,
  maxImgWidth:   900,
  imgQuality:    0.72,
  maxTextLen:    300,
  toastDuration: 3000,
  timerInterval: 1000,
  geocodeTimeout: 6000,
  defaultCenter: [31.7767, 35.2345],
  nominatim:     'https://nominatim.openstreetmap.org/reverse?format=json&addressdetails=1'
});
