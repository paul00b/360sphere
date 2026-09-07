import * as THREE from 'three';
import { Viewer, utils, DEFAULTS, CONSTANTS, events as coreEvents } from '@photo-sphere-viewer/core';
import { VirtualTourPlugin } from '@photo-sphere-viewer/virtual-tour-plugin';
import { MarkersPlugin } from '@photo-sphere-viewer/markers-plugin';
window.PSV = { Viewer, utils, DEFAULTS, CONSTANTS, events: coreEvents, VirtualTourPlugin, MarkersPlugin, THREE };
